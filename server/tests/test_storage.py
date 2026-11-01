"""One restart scenario, shared by SQLite and the optional isolated MySQL test DB."""
from __future__ import annotations

import csv
import io
from pathlib import Path
import zipfile

import pytest
from fastapi.testclient import TestClient
from sqlalchemy.engine import make_url
from sqlalchemy import Column, Integer, JSON, MetaData, String, Table, create_engine, insert, inspect, select

from server.app.common import new_uuid7
from server.app.main import create_app
from server.app.state import dev_users
from server.app.storage import ENTITIES, SqlMetadataStorage
from test_uploads import BASE, bearer, declaration, declare, image_decl, jpeg_bytes, png_bytes, put_image
from test_flow import wait_export


@pytest.fixture(params=['sqlite', 'mysql'])
def persistent_config(request, tmp_path):
    if request.param == 'sqlite':
        return f'sqlite+pysqlite:///{(tmp_path / "metadata.sqlite3").as_posix()}', tmp_path / 'media'
    root = Path(__file__).resolve().parents[2]
    source = root / 'deploy' / '.env.mysql.local'
    if not source.exists():
        pytest.skip('Local isolated MySQL configuration not present')
    values = dict(line.split('=', 1) for line in source.read_text(encoding='utf-8').splitlines()
                  if line.startswith('APP_') and '=' in line)
    url = values['APP_DATABASE_URL']
    if not (make_url(url).database or '').startswith('citrus_app_test_'):
        pytest.skip('Persistence smoke requires an isolated citrus_app_test_ database')
    return url, Path(values['APP_STORAGE_ROOT'])


def login_session(client, username, password='12345678'):
    response = client.post(f'{BASE}/auth/login', json={'username': username, 'password': password})
    assert response.status_code == 200
    return response.json()


def test_restart_retains_account_upload_review_and_frozen_export(persistent_config):
    url, media = persistent_config
    factory = lambda: create_app(database_url=url, storage_root=media, seed_demo=True)
    png, jpg = png_bytes(8, 6), jpeg_bytes(10, 7)
    a = image_decl(new_uuid7(), png, 'image/png', 8, 6)
    b = image_decl(new_uuid7(), jpg, 'image/jpeg', 10, 7)
    sample_id = new_uuid7()
    username = 'persist_' + new_uuid7().replace('-', '')[:20]

    with TestClient(factory()) as client:
        super_session = login_session(client, 'super')
        headers = bearer(super_session['access_token'])
        created = client.post(f'{BASE}/admin/users', headers=headers,
                              json={'username': username, 'display_name': '持久化试用', 'role': 'collector'})
        assert created.status_code == 201
        temporary_password = created.json()['temporary_password']
        collector = login_session(client, username, temporary_password)
        token = collector['access_token']
        upload_id = declare(client, token, declaration(sample_id, a, b))['upload_id']
        assert put_image(client, token, upload_id, a, png).status_code == 201

    # A new app/engine has to recover accounts, sessions, declaration, and the first file.
    with TestClient(factory()) as client:
        assert client.get(f'{BASE}/me', headers=bearer(token)).status_code == 200
        assert login_session(client, username, temporary_password)['user']['username'] == username
        status = client.get(f'{BASE}/uploads/{upload_id}', headers=bearer(token))
        assert status.status_code == 200
        assert status.json()['missing_images'] == [b['image_id']]
        assert status.json()['received_images'] == [a['image_id']]
        assert put_image(client, token, upload_id, a, png).status_code == 200
        assert put_image(client, token, upload_id, b, jpg).status_code == 201
        completed = client.post(f'{BASE}/uploads/{upload_id}/complete', headers=bearer(token))
        assert completed.status_code == 200
        admin = login_session(client, 'admin')['access_token']
        headers = bearer(admin)
        reviewer_headers = bearer(login_session(client, 'expert')['access_token'])
        review_body = {'expected_version': 0, 'review_status': 'corrected',
                       'final_cultivar_id': 'huyou', 'final_part': 'leaf', 'final_class_id': 'citrus_canker'}
        assert client.patch(f'{BASE}/samples/{sample_id}/review', headers=reviewer_headers, json=review_body).status_code == 200
        assert client.patch(f'{BASE}/samples/{sample_id}/review', headers=reviewer_headers, json=review_body).status_code == 409
        for image, usable in [(a, True), (b, False)]:
            body = {'expected_version': 0, 'quality_status': 'usable' if usable else 'unusable',
                    'quality_reason_code': None if usable else 'blur'}
            assert client.patch(f'{BASE}/samples/{sample_id}/images/{image["image_id"]}/quality',
                                headers=headers, json=body).status_code == 200
        # Exercise the full permitted idempotency-key length on MySQL too.
        export_headers = {**headers, 'Idempotency-Key': new_uuid7().ljust(128, 'x')}
        exported = client.post(f'{BASE}/exports', headers=export_headers, json={'format': 'dataset'})
        assert exported.status_code == 202
        export_id = exported.json()['export_id']
        assert wait_export(client, admin, export_id)['status'] == 'succeeded'
        # Later review changes must not change an existing export.
        assert client.patch(f'{BASE}/samples/{sample_id}/images/{a["image_id"]}/quality', headers=headers,
                            json={'expected_version': 1, 'quality_status': 'unusable',
                                  'quality_reason_code': 'blur'}).status_code == 200

    with TestClient(factory()) as client:
        headers = bearer(admin)
        detail = client.get(f'{BASE}/samples/{sample_id}', headers=headers)
        assert detail.status_code == 200
        assert detail.json()['review_version'] == 1
        selected = next(image for image in detail.json()['images'] if image['image_id'] == a['image_id'])
        assert selected['quality_version'] == 2
        replayed = client.post(f'{BASE}/exports', headers=export_headers, json={'format': 'dataset'})
        assert replayed.status_code == 202 and replayed.json()['export_id'] == export_id
        download = client.get(f'{BASE}/exports/{export_id}/download', headers=headers)
        assert download.status_code == 200
        with zipfile.ZipFile(io.BytesIO(download.content)) as archive:
            assert f'images/{a["image_id"]}.png' in archive.namelist()
            assert f'images/{b["image_id"]}.jpg' not in archive.namelist()
            rows = list(csv.DictReader(io.StringIO(archive.read('labels.csv').decode('utf-8'))))
            assert next(row for row in rows if row['image_id'] == a['image_id'])['class_id'] == 'citrus_canker'
        preview = client.get(f'{BASE}/media/sample-images/{a["image_id"]}?variant=preview&w=320', headers=headers)
        assert preview.status_code == 200 and preview.headers['content-type'].startswith('image/jpeg')
        with client.app.state.storage.transaction() as state:
            original_version = state.get_config()["policy_version"]
        with pytest.raises(RuntimeError, match='rollback'):
            with client.app.state.storage.transaction() as state:
                state.get_config()["policy_version"] += 1
                raise RuntimeError('rollback')
        with client.app.state.storage.transaction() as state:
            assert state.get_config()["policy_version"] == original_version


def test_legacy_v1_upgrade_preserves_accounts_and_old_tables(tmp_path):
    """One migration check; application startup also upgrades the existing MySQL test DB."""
    url = f'sqlite+pysqlite:///{(tmp_path / "old.sqlite3").as_posix()}'
    engine = create_engine(url)
    metadata = MetaData()
    legacy = {name: Table(f'meta_{name}', metadata,
                         Column('record_key', String(128), primary_key=True),
                         Column('rev', Integer, nullable=False), Column('payload', JSON, nullable=False))
              for name in ENTITIES}
    versions = Table('meta_schema', metadata, Column('name', String(32), primary_key=True),
                     Column('value', String(64), nullable=False))
    lock = Table('meta_lock', metadata, Column('id', Integer, primary_key=True), Column('rev', Integer))
    metadata.create_all(engine)
    user = dev_users()[0]
    user['display_name'] = '迁移前已有账号'
    with engine.begin() as connection:
        connection.execute(insert(versions).values(name='schema_version', value='1'))
        connection.execute(insert(lock).values(id=1, rev=0))
        connection.execute(insert(legacy['users']).values(record_key=user['user_id'], rev=1, payload=user))
        connection.execute(insert(legacy['config']).values(record_key='config', rev=1,
                           payload={'taxonomy_version': 7, 'policy_version': 3}))
    storage = SqlMetadataStorage(url)
    storage.initialize()
    with storage.transaction() as state:
        assert state.get_user(user["user_id"])["display_name"] == "迁移前已有账号"
        assert state.get_config()["taxonomy_version"] == 7 and state.get_config()["policy_version"] == 3
    with engine.connect() as connection:
        assert connection.execute(select(versions.c.value)).scalar_one() == '5'
        assert connection.execute(select(legacy['users'].c.record_key)).scalar_one() == user['user_id']
    assert 'payload' not in {column['name'] for column in inspect(engine).get_columns('users')}
    storage.close()
    engine.dispose()
