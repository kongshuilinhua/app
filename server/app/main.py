"""柑橘采集管理端服务端：应用工厂。

元数据默认持久化到本地 SQLite（`app/.runtime/metadata.sqlite3`），也可用 `APP_DATABASE_URL`
切到 MySQL/PostgreSQL，或用 `create_app("memory")` 获得仅供隔离测试的内存实现。
图片/ZIP字节通过可注入的FileStorage读写，不进入元数据库。
"""
from __future__ import annotations

import os
import tempfile
from contextlib import asynccontextmanager
from pathlib import Path
from typing import AsyncIterator

from fastapi import FastAPI
from fastapi.responses import FileResponse
from pydantic import BaseModel

from . import accounts, auth, exports, review, uploads, recognition, collection, diagnosis, phone_auth
from .common import (
    CONTRACT_VERSION,
    DomainError,
    hash_password,
    iso_utc,
    new_uuid7,
    register_exception_handlers,
    request_context_middleware,
)
from .state import default_classes, default_cultivars, dev_users, make_user
from .data_access import FileStorage, MetadataStorage, StorageError
from .files import LocalFileStorage
from .resources import ExportWork, ServiceLimits, UploadWork
from anyio import to_thread
from .storage import MemoryStorage, SqlMetadataStorage

__all__ = ["create_app", "app", "HealthResponse"]

# app/ 根目录；默认持久目录放在 app/.runtime（不提交）。
APP_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_RUNTIME_DIR = APP_ROOT / ".runtime"
DATABASE_FILENAME = "metadata.sqlite3"


class HealthResponse(BaseModel):
    status: str
    version: str
    time: str


def _flag(value: str | None, default: bool) -> bool:
    if value is None or value.strip() == "":
        return default
    return value.strip().lower() not in {"false", "0", "no", "off"}


def _sqlite_url(path: Path) -> str:
    return f"sqlite+pysqlite:///{path.as_posix()}"


def _build_storage(
    database_url: str | None,
    metadata: MetadataStorage | None,
    runtime_dir: Path,
) -> MetadataStorage:
    if metadata is not None:
        return metadata
    url = (database_url if database_url is not None else os.environ.get("APP_DATABASE_URL", "") or "").strip()
    if url in {"memory", "memory://"}:
        return MemoryStorage()
    if not url:
        runtime_dir.mkdir(parents=True, exist_ok=True)
        url = _sqlite_url(runtime_dir / DATABASE_FILENAME)
    return SqlMetadataStorage(url)


def _seed(
    storage: MetadataStorage,
    *,
    demo: bool,
    username: str | None,
    password: str | None,
) -> None:
    """只在空库/空内存视图写入种子，绝不覆盖既有账号或字典。"""
    with storage.transaction() as store:
        has_users = store.users_exist()
        if not has_users and not demo and (not username or not password):
            raise RuntimeError("空库初始化需要 APP_BOOTSTRAP_USERNAME 与 APP_BOOTSTRAP_PASSWORD")
        store.seed_catalog(default_cultivars(), default_classes())
        if not has_users:
            users = dev_users() if demo else [make_user(new_uuid7(), username or "", username or "",
                                                        "super_admin", hash_password(password or ""))]
            for user in users:
                store.add_user(user)


def create_app(
    database_url: str | None = None,
    *,
    metadata: MetadataStorage | None = None,
    files: FileStorage | None = None,
    storage_root: str | os.PathLike[str] | None = None,
    seed_demo: bool | None = None,
    bootstrap_username: str | None = None,
    bootstrap_password: str | None = None,
    limits: ServiceLimits | None = None,
    recognizer=None,
    detector=None,
    sms_provider=None,
) -> FastAPI:
    """新建独立应用。

    - `database_url="memory"`：仅内存实现，供隔离测试；
    - 未提供地址时用 `APP_DATABASE_URL`，再缺省则用 `app/.runtime` 下的 SQLite 文件；
    - `storage_root`/`APP_STORAGE_ROOT` 指定开发运行目录（元数据库文件与 files 子目录）。
    """
    limits = limits or ServiceLimits.from_env()
    upload_work = UploadWork(limits.upload_concurrency)
    export_work = ExportWork(limits)
    runtime_dir = Path(storage_root or os.environ.get("APP_STORAGE_ROOT") or DEFAULT_RUNTIME_DIR)

    storage = _build_storage(database_url, metadata, runtime_dir)
    storage.initialize()

    temporary_files = None
    if files is None and isinstance(storage, MemoryStorage) and storage_root is None:
        temporary_files = tempfile.TemporaryDirectory(prefix="citrus-app-test-")
        files_root = Path(temporary_files.name)
    else:
        files_root = runtime_dir / "files"
    file_storage = files if files is not None else LocalFileStorage(files_root)

    demo = seed_demo if seed_demo is not None else _flag(os.environ.get("APP_SEED_DEMO"), True)
    _seed(
        storage,
        demo=demo,
        username=bootstrap_username or os.environ.get("APP_BOOTSTRAP_USERNAME"),
        password=bootstrap_password or os.environ.get("APP_BOOTSTRAP_PASSWORD"),
    )

    @asynccontextmanager
    async def lifespan(_app: FastAPI) -> AsyncIterator[None]:
        try:
            # Single API process: interrupted work remains retryable with its original snapshot.
            exports.recover_interrupted(storage)
            yield
        finally:
            await to_thread.run_sync(export_work.close)
            storage.close()
            if temporary_files is not None:
                temporary_files.cleanup()

    application = FastAPI(
        title="柑橘病虫害采集管理端 API",
        version=CONTRACT_VERSION,
        lifespan=lifespan,
        description="认证、配置、账号管理与采集/审核/导出的最小实现。基础路径 /api/v1。",
    )
    application.state.storage = storage
    application.state.storage_root = str(runtime_dir)
    application.state.files_root = str(files_root)
    application.state.files = file_storage
    application.state.limits = limits
    application.state.upload_work = upload_work
    application.state.export_work = export_work
    application.state.recognizer = recognizer or recognition.LocalClassifier(runtime_dir)
    application.state.detector = detector or recognition.LocalDetector(runtime_dir)
    application.state.recognition_work = UploadWork(1)
    application.state.diagnosis_key = diagnosis.signing_key(files_root)
    application.state.sms_provider = sms_provider or phone_auth.AliyunSmsProvider()
    if isinstance(storage, MemoryStorage):
        # 仅供隔离测试直接安排内存夹具；所有业务模块都通过storage.transaction访问。
        application.state.store = storage.store

    application.middleware("http")(request_context_middleware)
    register_exception_handlers(application)

    application.include_router(auth.router)
    application.include_router(phone_auth.router)
    application.include_router(accounts.router)
    application.include_router(uploads.router)
    application.include_router(collection.router)
    application.include_router(review.router)
    application.include_router(exports.router)
    application.include_router(recognition.router)
    application.include_router(diagnosis.router)

    @application.get("/api/v1/health", tags=["health"], response_model=HealthResponse, summary="进程存活")
    def health() -> HealthResponse:
        return HealthResponse(status="ok", version=CONTRACT_VERSION, time=iso_utc())

    @application.get("/api/v1/health/ready", tags=["health"], response_model=HealthResponse, summary="依赖就绪")
    def health_ready() -> HealthResponse:
        try:
            storage.ping()
            file_storage.ping()
        except StorageError:
            raise DomainError(503, "UNAVAILABLE", "数据库暂时不可用，请稍后重试") from None
        return HealthResponse(status="ok", version=CONTRACT_VERSION, time=iso_utc())

    # Serve the built management UI from the same origin as /api; Vite is only needed for HMR.
    admin_dist = (APP_ROOT / "admin" / "dist").resolve()
    if (admin_dist / "index.html").is_file():
        @application.get("/{path:path}", include_in_schema=False)
        def admin_page(path: str):
            if path == "api" or path.startswith("api/"):
                raise DomainError(404, "NOT_FOUND", "接口不存在")
            candidate = (admin_dist / path).resolve()
            if candidate.is_relative_to(admin_dist) and candidate.is_file():
                # Windows registry MIME mappings can label .js as text/plain; browsers reject modules.
                media_type = {".js": "text/javascript", ".css": "text/css"}.get(candidate.suffix)
                return FileResponse(candidate, media_type=media_type)
            return FileResponse(admin_dist / "index.html")

    return application


app = create_app()
