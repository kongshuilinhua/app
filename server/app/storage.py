"""Private SQL/Memory adapters for data_access contracts. Legacy migration remains v2."""
from __future__ import annotations

import json
from copy import deepcopy
from contextlib import contextmanager
from dataclasses import fields
from datetime import datetime, timezone
from typing import Any, Iterator

from sqlalchemy import inspect, DateTime, MetaData, Table, create_engine, delete, event, func, insert, or_, select, text, update
from sqlalchemy.exc import IntegrityError, SQLAlchemyError
from sqlalchemy.orm import Session, sessionmaker

from .data_access import (DataTransaction, MetadataStorage, RecordPage, SampleFilter,
                          StorageError, StorageConflict, StorageUnavailable, session_key)
from .state import Store, build_store
from .schema import ENTITY_ORDER, PRIMARY_KEYS, SCHEMA_VERSION, build_schema

KEY_LEN = 128  # 记录键：UUID 36 / 令牌摘要 64 / 幂等键 <100；兼顾 MySQL utf8mb4 索引长度
HASH_LEN = 64

# 仅旧v1迁移使用的实体；在线事务不加载Store。
ENTITIES: tuple[str, ...] = (
    "users",
    "sessions",
    "cultivars",
    "classes",
    "uploads",
    "samples",
    "exports",
    "idempotency",
    "config",
)


def _record_key_for_session(key: str) -> str:
    """内存视图里键可能是原始令牌，落库前统一成摘要（已是 64 位十六进制则不重复散列）。"""
    if len(key) == HASH_LEN and all(ch in "0123456789abcdef" for ch in key):
        return key
    return session_key(key)


# ---------------- JSON 编解码 ----------------

def _to_jsonable(value: Any) -> Any:
    if isinstance(value, datetime):
        dt = value if value.tzinfo else value.replace(tzinfo=timezone.utc)
        return {"__dt__": dt.astimezone(timezone.utc).isoformat()}
    if isinstance(value, dict):
        return {str(k): _to_jsonable(v) for k, v in value.items()}
    if isinstance(value, (list, tuple)):
        return [_to_jsonable(v) for v in value]
    if isinstance(value, (bytes, bytearray, memoryview)):
        raise StorageError("元数据存储不接受二进制字节（图片/ZIP 请走文件模块）")
    if value is None or isinstance(value, (str, int, float, bool)):
        return value
    return str(value)


def _from_jsonable(value: Any) -> Any:
    if isinstance(value, dict):
        if set(value) == {"__dt__"}:
            parsed = datetime.fromisoformat(str(value["__dt__"]))
            return parsed if parsed.tzinfo else parsed.replace(tzinfo=timezone.utc)
        return {k: _from_jsonable(v) for k, v in value.items()}
    if isinstance(value, list):
        return [_from_jsonable(v) for v in value]
    return value


def _canonical(value: Any) -> str:
    return json.dumps(_to_jsonable(value), sort_keys=True, ensure_ascii=False, separators=(",", ":"))


# ---------------- 视图 <-> 记录 ----------------

def _payload(entity: str, record: dict[str, Any]) -> dict[str, Any]:
    if entity == "sessions":
        # 内存模式的记录可能带原始 token，落库与比较时都剔除。
        return {k: v for k, v in record.items() if k != "token"}
    if entity == "exports":
        # ZIP 字节属于文件层，元数据只保留状态与哈希；导出模块接入文件存储前先剔除。
        return {k: v for k, v in record.items() if k != "zip_bytes"}
    return record


def _records(store: Store, entity: str) -> dict[str, dict[str, Any]]:
    if entity == "users":
        return {row["user_id"]: row for row in store.users}
    if entity == "cultivars":
        return {row["cultivar_id"]: row for row in store.cultivars}
    if entity == "classes":
        return {row["class_id"]: row for row in store.classes}
    if entity == "sessions":
        return {_record_key_for_session(key): row for key, row in store.sessions.items()}
    if entity == "uploads":
        return dict(store.uploads)
    if entity == "samples":
        return dict(store.samples)
    if entity == "exports":
        return dict(store.exports)
    if entity == "idempotency":
        return dict(store.idempotency)
    if entity == "config":
        return {
            "config": {
                "taxonomy_version": store.taxonomy_version,
                "policy_version": store.policy_version,
            }
        }
    raise StorageError(f"未知实体: {entity}")


def _relink_shared_images(store: Store) -> None:
    """恢复 uploads 与 samples 共享同一图片 dict 的关系（上传记录的版本为准）。"""
    canonical: dict[str, dict[str, Any]] = {}

    def canon(image: Any) -> Any:
        if not isinstance(image, dict):
            return image
        image_id = image.get("image_id")
        if not isinstance(image_id, str):
            return image
        existing = canonical.get(image_id)
        if existing is not None:
            return existing
        canonical[image_id] = image
        return image

    for upload in store.uploads.values():
        images = upload.get("images")
        if isinstance(images, dict):
            upload["images"] = {key: canon(value) for key, value in images.items()}
        elif isinstance(images, list):
            upload["images"] = [canon(value) for value in images]

    for sample in store.samples.values():
        images = sample.get("images")
        if isinstance(images, list):
            sample["images"] = [canon(value) for value in images]
        elif isinstance(images, dict):
            sample["images"] = {key: canon(value) for key, value in images.items()}


# ---------------- 接口 ----------------

class MemoryStorage:
    """仅用于隔离测试：单进程内存视图，不持久化。"""

    backend = "memory"

    def __init__(self, store: Store | None = None) -> None:
        self._store = store if store is not None else build_store()
        self._closed = False

    def initialize(self) -> None:
        self.ping()

    @property
    def store(self) -> Store:
        return self._store

    @contextmanager
    def transaction(self, *, write: bool = True) -> Iterator[DataTransaction]:
        if self._closed:
            raise StorageUnavailable("内存存储已关闭")
        # 隔离测试的事务锁；异常时恢复整个元数据视图。
        with self._store.lock:
            before = deepcopy({item.name: getattr(self._store, item.name)
                               for item in fields(self._store) if item.name != "lock"})
            try:
                target = self._store if write else Store(**deepcopy(before))
                access = _MemoryTransaction(target, write=write)
                yield access
                access.flush()
            except BaseException:
                for name, value in before.items():
                    setattr(self._store, name, value)
                raise

    def ping(self) -> None:
        if self._closed:
            raise StorageUnavailable("内存存储已关闭")

    def close(self) -> None:
        self._closed = True


class SqlMetadataStorage:
    """SQLAlchemy 2 通用适配器：sqlite+pysqlite / mysql+pymysql / postgresql+psycopg。"""

    def __init__(self, url: str) -> None:
        if not url:
            raise StorageUnavailable("缺少数据库连接配置")
        self._url = url
        connect_args: dict[str, Any] = {}
        if url.startswith("sqlite"):
            # 多实例写并发靠写锁串行化，这里只放宽等待时间。
            connect_args = {"timeout": 30}
        self._engine = create_engine(url, future=True, pool_pre_ping=True, connect_args=connect_args)
        self._session_factory = sessionmaker(bind=self._engine, expire_on_commit=False)
        self._closed = False
        self._metadata, self._tables, self._lock_table, self._schema_table = build_schema()
        if self._engine.dialect.name == "sqlite":
            event.listen(self._engine, "connect", _sqlite_foreign_keys)

    @property
    def backend(self) -> str:
        return self._engine.dialect.name

    # ---- 生命周期 ----

    def initialize(self) -> None:
        if self._closed:
            raise StorageUnavailable("数据库存储已关闭")
        try:
            self._metadata.create_all(self._engine)
            with self._engine.begin() as conn:
                row = conn.execute(
                    select(self._schema_table.c.value).where(self._schema_table.c.name == "schema_version")
                ).first()
                if row is None:
                    conn.execute(insert(self._schema_table).values(name="schema_version", value=SCHEMA_VERSION))
                elif str(row[0]) not in {"1", "2", "3", "4", SCHEMA_VERSION}:
                    raise StorageError(
                        f"数据库 schema_version={row[0]} 与本版 {SCHEMA_VERSION} 不一致；请先手动迁移，不做隐式破坏性迁移"
                    )
                if conn.execute(select(self._lock_table.c.id).where(self._lock_table.c.id == 1)).first() is None:
                    try:
                        # SAVEPOINT：并发初始化时另一方插入失败不应把整个初始化事务置为失败。
                        with conn.begin_nested():
                            conn.execute(insert(self._lock_table).values(id=1, rev=0))
                    except IntegrityError:
                        # 另一实例同时初始化；锁行已存在即视为成功。
                        pass
            self._migrate_v1()
            self._migrate_collection_columns()
        except StorageError:
            raise
        except SQLAlchemyError as exc:
            raise StorageUnavailable("数据库初始化失败") from exc

    def _migrate_collection_columns(self):
        # Add nullable columns only: retain every historical row and its original values.
        additions = {"samples": ("batch_id", "batch_snapshot", "latitude", "longitude", "location_accuracy_m"),
                     "export_items": ("collection_metadata",), "phone_identities": ("masked_phone",)}
        with self._engine.begin() as conn:
            for name, columns in additions.items():
                existing = {c["name"] for c in inspect(conn).get_columns(name)}
                for column_name in columns:
                    if column_name not in existing:
                        column = self._tables[name].c[column_name]
                        kind = column.type.compile(dialect=conn.dialect)
                        conn.execute(text(f"ALTER TABLE {name} ADD COLUMN {column_name} {kind} NULL"))
            conn.execute(update(self._schema_table).where(self._schema_table.c.name == "schema_version").values(value=SCHEMA_VERSION))

    def close(self) -> None:
        if not self._closed:
            self._closed = True
            self._engine.dispose()

    def ping(self) -> None:
        if self._closed:
            raise StorageUnavailable("数据库存储已关闭")
        try:
            with self._engine.connect() as conn:
                conn.execute(text("SELECT 1"))
        except SQLAlchemyError as exc:
            # 不输出 URL/凭据。
            raise StorageUnavailable("数据库连接不可用") from exc

    # ---- 事务 ----

    @contextmanager
    def transaction(self, *, write: bool = True) -> Iterator[DataTransaction]:
        if self._closed:
            raise StorageUnavailable("数据库存储已关闭")
        session: Session = self._session_factory()
        try:
            with session.begin():
                # SQLite serializes writers inherently; PG/MySQL lock only selected records.
                if write and self._engine.dialect.name == "sqlite":
                    self._acquire_write_lock(session)
                access = _SqlTransaction(session, self._tables, write=write)
                yield access
                access.flush()
        except StorageError:
            raise
        except IntegrityError as exc:
            raise StorageConflict("写入冲突：记录已被其他事务修改") from exc
        except SQLAlchemyError as exc:
            raise StorageUnavailable("数据库操作失败") from exc
        finally:
            session.close()

    def _acquire_write_lock(self, session: Session) -> None:
        """用数据库中的单行锁串行化短事务；不能只靠进程锁。"""
        dialect = self._engine.dialect.name
        if dialect == "sqlite":
            # SQLite 没有 FOR UPDATE；首条写语句即取得写锁（等价于尽早 BEGIN IMMEDIATE）。
            session.execute(
                update(self._lock_table).where(self._lock_table.c.id == 1).values(rev=self._lock_table.c.rev + 1)
            )
        else:
            session.execute(select(self._lock_table.c.rev).where(self._lock_table.c.id == 1).with_for_update())

    def _migrate_v1(self) -> None:
        """Copy v1 records once; old tables and files are deliberately preserved."""
        with self._session_factory() as session, session.begin():
            self._acquire_write_lock(session)
            version = session.execute(select(self._schema_table.c.value).where(
                self._schema_table.c.name == "schema_version")).scalar_one()
            if version != "1":
                return
            legacy = MetaData()
            store = Store()
            for entity in ENTITIES:
                table = Table(f"meta_{entity}", legacy, autoload_with=session.connection())
                for row in session.execute(select(table)).mappings():
                    key = row["record_key"]
                    record = _from_jsonable(row["payload"])
                    if entity in {"users", "cultivars", "classes"}:
                        getattr(store, entity).append(record)
                    elif entity == "config":
                        store.taxonomy_version = record["taxonomy_version"]
                        store.policy_version = record["policy_version"]
                    else:
                        getattr(store, entity)[key] = record
            _relink_shared_images(store)
            self._commit(session, store)
            session.execute(update(self._schema_table).where(
                self._schema_table.c.name == "schema_version").values(value=SCHEMA_VERSION))

    def _commit(self, session: Session, store: Store) -> None:
        current = _flat_rows(store)
        # Delete children first; normal app operations use state changes rather than hard deletion.
        for entity in reversed(ENTITY_ORDER):
            table = self._tables[entity]
            for key in set(store._loaded.get(entity, {})) - set(current[entity]):
                session.execute(delete(table).where(*_where(table, entity, key)))
        for entity in ENTITY_ORDER:
            table = self._tables[entity]
            loaded = store._loaded.get(entity, {})
            for key, record in current[entity].items():
                values = _sql_row(table, record)
                if key in loaded:
                    if _canonical(values) != loaded[key]:
                        session.execute(update(table).where(*_where(table, entity, key)).values(**values))
                else:
                    session.execute(insert(table).values(**values))


def _sqlite_foreign_keys(connection, _record):
    connection.execute("PRAGMA foreign_keys=ON")


def _key(entity, row):
    return tuple(row[name] for name in PRIMARY_KEYS[entity])


def _where(table, entity, key):
    return [table.c[name] == value for name, value in zip(PRIMARY_KEYS[entity], key)]


def _sql_row(table, record):
    values = {}
    for column in table.columns:
        value = record.get(column.name)
        if value is not None and isinstance(column.type, DateTime):
            if isinstance(value, str):
                value = datetime.fromisoformat(value.replace("Z", "+00:00"))
            value = value.replace(tzinfo=timezone.utc) if value.tzinfo is None else value.astimezone(timezone.utc)
        elif isinstance(value, (dict, list)):
            value = _to_jsonable(value)
        values[column.name] = value
    return values


def _read_row(table, row):
    for column in table.columns:
        value = row[column.name]
        if value is not None and isinstance(column.type, DateTime):
            value = value.replace(tzinfo=timezone.utc) if value.tzinfo is None else value.astimezone(timezone.utc)
            row[column.name] = value if column.name == "expires_at" else value.isoformat().replace("+00:00", "Z")
        elif isinstance(value, (dict, list)):
            row[column.name] = _from_jsonable(value)
    return row


def _flat_rows(store):
    """Split the existing business view into relational rows, with a single image record."""
    rows = {entity: {} for entity in ENTITY_ORDER}

    def add(entity, record):
        rows[entity][_key(entity, record)] = record

    for entity in ("users", "cultivars", "classes"):
        for record in getattr(store, entity):
            add(entity, record)
    add("config", {"config_key": "config", "taxonomy_version": store.taxonomy_version,
                   "policy_version": store.policy_version})
    for key, record in store.sessions.items():
        add("sessions", {**_payload("sessions", record), "token_hash": _record_key_for_session(key)})
    for entity in ("batches", "questions"):
        for record in getattr(store, entity).values():
            add(entity, record)
    for upload in store.uploads.values():
        add("uploads", upload)
    for sample in store.samples.values():
        add("samples", sample)
        for index, image in enumerate(sample["images"]):
            add("images", {**image, "sample_id": sample["sample_id"], "sort_index": index})
    for job in store.exports.values():
        add("exports", job)
        for index, item in enumerate(job["items"]):
            add("export_items", {**item, "export_id": job["export_id"], "sort_index": index})
    for key, record in store.idempotency.items():
        add("idempotency", {**record, "key_hash": key})
    return rows


class _ObjectAccess:
    """Private shared adapter logic; only DataTransaction's named operations are public."""

    def get_batch(self, batch_id):
        return self._one('batches', batch_id=batch_id)

    def add_batch(self, record):
        self._add('batches', record)

    def get_question(self, question_id):
        return self._one('questions', question_id=question_id)

    def add_question(self, record):
        self._add('questions', record)

    def list_batches(self, owner_id, page, page_size):
        return self._owned_page('batches', owner_id, page, page_size)

    def list_questions(self, owner_id, page, page_size):
        return self._owned_page('questions', owner_id, page, page_size)

    def get_phone_identity(self, phone_key):
        return self._one('phone_identities', phone_key=phone_key)

    def find_phone_for_user(self, user_id):
        return self._one('phone_identities', user_id=user_id)

    def add_phone_identity(self, record):
        self._add('phone_identities', record)

    def get_sms_state(self, key):
        return self._one('sms_states', record_key=key)

    def add_sms_state(self, record):
        self._add('sms_states', record)

    def get_user(self, user_id):
        return self._one('users', user_id=user_id)

    def find_user(self, username):
        return self._one('users', username=username)

    def users_exist(self):
        return bool(self._many('users', limit=1))

    def list_users(self, page, page_size):
        return self._page('users', page, page_size, order=('created_at', 'user_id'))

    def active_superadmins(self):
        return self._many('users', role='super_admin', is_active=True)

    def add_user(self, user):
        self._add('users', user)

    def get_session(self, token):
        return self._one('sessions', token_hash=_record_key_for_session(token))

    def add_session(self, token, record):
        self._add('sessions', {**record, 'token_hash': _record_key_for_session(token)}, token=token)

    def revoke_session(self, session_id):
        for row in self._many('sessions', session_id=session_id):
            row['revoked'] = True

    def revoke_user_sessions(self, user_id):
        for row in self._many('sessions', user_id=user_id):
            row['revoked'] = True

    def get_config(self):
        row = self._one('config', config_key='config')
        if row is None:
            row = {'config_key': 'config', 'taxonomy_version': 1, 'policy_version': 1}
            self._add('config', row)
        return row

    def list_cultivars(self):
        return self._many('cultivars')

    def list_classes(self):
        return self._many('classes')

    def get_cultivar(self, cultivar_id):
        return None if cultivar_id is None else self._one('cultivars', cultivar_id=cultivar_id)

    def get_class(self, class_id):
        return None if class_id is None else self._one('classes', class_id=class_id)

    def seed_catalog(self, cultivars, classes):
        self.get_config()
        if not self.list_cultivars():
            for row in cultivars:
                self._add('cultivars', row)
        if not self.list_classes():
            for row in classes:
                self._add('classes', row)

    def _sample(self, row):
        if row is not None and 'images' not in row:
            row['images'] = self._many('images', sample_id=row['sample_id'])
            row['images'].sort(key=lambda image: image['sort_index'])
        return row

    def get_sample(self, sample_id):
        return self._sample(self._one('samples', sample_id=sample_id))

    def get_upload(self, upload_id):
        row = self._one('uploads', upload_id=upload_id)
        if row is not None and 'images' not in row:
            sample = self.get_sample(row['sample_id'])
            images = sample['images'] if sample else []
            row['images'] = {image['image_id']: image for image in images}
            row['image_order'] = [image['image_id'] for image in images]
        return row

    def find_upload_for_image(self, image_id):
        # Discovery is read-only; then get_upload consistently locks upload -> sample -> images.
        upload_id = self._upload_id_for_image(image_id)
        return None if upload_id is None else self.get_upload(upload_id)

    def add_collection(self, upload, sample):
        self._add('uploads', upload)
        self._add('samples', sample)
        for index, image in enumerate(sample['images']):
            image.update(sample_id=sample['sample_id'], sort_index=index)
            self._add('images', image)

    def list_samples(self, filters, page, page_size):
        rows, total = self._sample_rows(filters, page=page, page_size=page_size)
        return RecordPage([self._sample(row) for row in rows], total)

    def export_samples(self, filters):
        rows, _ = self._sample_rows(filters)
        return [self._sample(row) for row in rows]

    def get_export(self, export_id):
        row = self._one('exports', export_id=export_id)
        if row is not None and 'items' not in row:
            row['items'] = self._many('export_items', export_id=export_id)
            row['items'].sort(key=lambda item: item['sort_index'])
        return row

    def add_export(self, job):
        self._add('exports', job)
        for index, item in enumerate(job['items']):
            self._add('export_items', {**item, 'export_id': job['export_id'], 'sort_index': index})

    def list_exports(self, page, page_size):
        return self._page('exports', page, page_size, order=('created_at', 'export_id'))

    def interrupted_exports(self):
        return self._many('exports', status=('queued', 'running'))

    def get_idempotency(self, key):
        return self._one('idempotency', key_hash=key)

    def add_idempotency(self, key, record):
        self._add('idempotency', {**record, 'key_hash': key})


class _SqlTransaction(_ObjectAccess):
    def __init__(self, session, tables, *, write):
        self._session = session
        self._tables = tables
        self._write = write
        self._rows = {}
        self._before = {}

    def _track(self, entity, raw):
        table = self._tables[entity]
        row = _read_row(table, dict(raw))
        slot = (entity, _key(entity, row))
        if slot not in self._rows:
            self._rows[slot] = row
            self._before[slot] = deepcopy(_sql_row(table, row))
        return self._rows[slot]

    def _select(self, statement):
        if self._write:
            statement = statement.with_for_update()
        return self._session.execute(statement).mappings()

    def _conditions(self, entity, criteria):
        table = self._tables[entity]
        return [table.c[key].in_(value) if isinstance(value, tuple) else table.c[key] == value
                for key, value in criteria.items()]

    def _one(self, entity, **criteria):
        for (name, _), row in self._rows.items():
            if name == entity and all(row.get(key) == value for key, value in criteria.items()):
                return row
        table = self._tables[entity]
        row = self._select(select(table).where(*self._conditions(entity, criteria)).limit(1)).first()
        return None if row is None else self._track(entity, row)

    def _many(self, entity, *, limit=None, **criteria):
        table = self._tables[entity]
        statement = select(table).where(*self._conditions(entity, criteria)).order_by(
            *(table.c[key] for key in PRIMARY_KEYS[entity]))
        if limit is not None:
            statement = statement.limit(limit)
        return [self._track(entity, row) for row in self._select(statement)]

    def _add(self, entity, row, **_):
        if not self._write:
            raise StorageError('只读事务不允许写入')
        slot = (entity, _key(entity, row))
        if slot in self._rows or self._one(entity, **dict(zip(PRIMARY_KEYS[entity], slot[1]))) is not None:
            raise StorageConflict('记录已存在')
        self._rows[slot] = row
        self._before[slot] = None

    def _owned_page(self, entity, owner_id, page, page_size):
        table = self._tables[entity]
        conditions = [] if owner_id is None else [table.c.owner_id == owner_id]
        total = self._session.execute(select(func.count()).select_from(table).where(*conditions)).scalar_one()
        statement = select(table).where(*conditions).order_by(table.c.created_at.desc(), table.c[PRIMARY_KEYS[entity][0]].desc()).offset((page - 1) * page_size).limit(page_size)
        return RecordPage([self._track(entity, row) for row in self._select(statement)], total)

    def _page(self, entity, page, page_size, *, order):
        table = self._tables[entity]
        total = self._session.execute(select(func.count()).select_from(table)).scalar_one()
        statement = select(table).order_by(*(table.c[key].desc() for key in order)).offset(
            (page - 1) * page_size).limit(page_size)
        return RecordPage([self._track(entity, row) for row in self._select(statement)], total)

    def _upload_id_for_image(self, image_id):
        images, samples = self._tables['images'], self._tables['samples']
        return self._session.execute(select(samples.c.upload_id).select_from(
            images.join(samples, images.c.sample_id == samples.c.sample_id)).where(
                images.c.image_id == image_id)).scalar_one_or_none()

    def _sample_rows(self, filters, *, page=None, page_size=None):
        table = self._tables['samples']
        conditions = [table.c.upload_state == 'complete']
        if filters.review_statuses is not None:
            conditions.append(table.c.review_status.in_(filters.review_statuses))
        for name in ('excluded', 'final_cultivar_id', 'final_class_id', 'owner_id', 'batch_id'):
            value = getattr(filters, name)
            if value is not None:
                conditions.append(table.c[name] == value)
        if filters.uploaded_from is not None:
            conditions.append(table.c.upload_completed_at >= filters.uploaded_from)
        if filters.uploaded_to is not None:
            conditions.append(table.c.upload_completed_at < filters.uploaded_to)
        if filters.query:
            # startswith(autoescape=True) preserves literal '%' and '_' prefix semantics.
            conditions.append(or_(func.lower(table.c.sample_id) == filters.query,
                                  func.lower(table.c.tree_id).startswith(filters.query, autoescape=True)))
        total = self._session.execute(select(func.count()).select_from(table).where(*conditions)).scalar_one()
        statement = select(table).where(*conditions).order_by(
            table.c.upload_completed_at.desc(), table.c.sample_id.desc())
        if page is not None:
            statement = statement.offset((page - 1) * page_size).limit(page_size)
        return [self._track('samples', row) for row in self._select(statement)], total

    def flush(self):
        # Persist only touched rows/columns; child records and parent updates share one transaction.
        for entity in ENTITY_ORDER:
            table = self._tables[entity]
            for slot, row in list(self._rows.items()):
                if slot[0] != entity:
                    continue
                before = self._before[slot]
                current = _sql_row(table, row)
                if before is not None and _canonical(current) == _canonical(before):
                    continue
                if not self._write:
                    raise StorageError('只读事务不允许修改记录')
                if before is None:
                    self._session.execute(insert(table).values(**current))
                else:
                    changed = {key: value for key, value in current.items()
                               if _canonical(value) != _canonical(before[key])}
                    result = self._session.execute(update(table).where(*_where(table, entity, slot[1])).values(**changed))
                    if result.rowcount != 1:
                        raise StorageConflict('记录已被其他事务修改')


class _MemoryTransaction(_ObjectAccess):
    def __init__(self, store, *, write):
        self._store = store
        self._write = write

    def _all(self, entity):
        if entity == 'config':
            if not hasattr(self, '_config'):
                self._config = {'config_key': 'config', 'taxonomy_version': self._store.taxonomy_version,
                                'policy_version': self._store.policy_version}
            return [self._config]
        if entity == 'images':
            rows = []
            for sample in self._store.samples.values():
                for index, image in enumerate(sample['images']):
                    image.setdefault('sample_id', sample['sample_id'])
                    image.setdefault('sort_index', index)
                    rows.append(image)
            return rows
        if entity == 'export_items':
            return [{**item, 'export_id': job['export_id'], 'sort_index': index}
                    for job in self._store.exports.values() for index, item in enumerate(job['items'])]
        value = getattr(self._store, entity)
        if entity == 'sessions':
            return [{**row, 'token_hash': _record_key_for_session(key), '_record': row} for key, row in value.items()]
        if entity == 'idempotency':
            return [{**row, 'key_hash': key} for key, row in value.items()]
        return list(value.values()) if isinstance(value, dict) else value

    def _one(self, entity, **criteria):
        return next(iter(self._many(entity, limit=1, **criteria)), None)

    def _many(self, entity, *, limit=None, **criteria):
        rows = [row for row in self._all(entity) if all(
            row.get(key) in value if isinstance(value, tuple) else row.get(key) == value
            for key, value in criteria.items())]
        if limit is not None:
            rows = rows[:limit]
        return [row.get('_record', row) for row in rows]

    def _add(self, entity, row, *, token=None):
        if not self._write:
            raise StorageError('只读事务不允许写入')
        criteria = {key: row[key] for key in PRIMARY_KEYS[entity]}
        if entity in ('images', 'export_items'):
            return
        if self._one(entity, **criteria) is not None:
            raise StorageConflict('记录已存在')
        value = getattr(self._store, entity, None)
        if isinstance(value, list):
            value.append(row)
        elif entity == 'sessions':
            value[token] = {key: item for key, item in row.items() if key != 'token_hash'}
        elif entity == 'idempotency':
            value[row['key_hash']] = {key: item for key, item in row.items() if key != 'key_hash'}
        elif entity not in ('images', 'export_items'):
            value[row[PRIMARY_KEYS[entity][0]]] = row

    def _owned_page(self, entity, owner_id, page, page_size):
        rows = self._many(entity, **({'owner_id': owner_id} if owner_id else {}))
        rows.sort(key=lambda row: (row['created_at'], row[PRIMARY_KEYS[entity][0]]), reverse=True)
        start = (page - 1) * page_size
        return RecordPage(rows[start:start + page_size], len(rows))

    def _page(self, entity, page, page_size, *, order):
        rows = sorted(self._all(entity), key=lambda row: tuple(row[key] for key in order), reverse=True)
        start = (page - 1) * page_size
        return RecordPage(rows[start:start + page_size], len(rows))

    def _upload_id_for_image(self, image_id):
        return next((upload['upload_id'] for upload in self._store.uploads.values()
                     if image_id in upload['images']), None)

    def _sample_rows(self, filters, *, page=None, page_size=None):
        rows = []
        for sample in self._store.samples.values():
            if sample['upload_state'] != 'complete':
                continue
            if filters.review_statuses is not None and sample['review_status'] not in filters.review_statuses:
                continue
            if any(getattr(filters, name) is not None and sample.get(name) != getattr(filters, name)
                   for name in ('excluded', 'final_cultivar_id', 'final_class_id', 'owner_id', 'batch_id')):
                continue
            value = sample['upload_completed_at']
            completed = datetime.fromisoformat(value.replace('Z', '+00:00')) if isinstance(value, str) else value
            if filters.uploaded_from is not None and (completed is None or completed < filters.uploaded_from):
                continue
            if filters.uploaded_to is not None and (completed is None or completed >= filters.uploaded_to):
                continue
            if filters.query and not (sample['sample_id'].lower() == filters.query or
                                      (sample.get('tree_id') or '').lower().startswith(filters.query)):
                continue
            rows.append(sample)
        rows.sort(key=lambda row: (row['upload_completed_at'], row['sample_id']), reverse=True)
        total = len(rows)
        if page is not None:
            start = (page - 1) * page_size
            rows = rows[start:start + page_size]
        return rows, total

    def flush(self):
        if hasattr(self, '_config') and self._write:
            self._store.taxonomy_version = self._config['taxonomy_version']
            self._store.policy_version = self._config['policy_version']
