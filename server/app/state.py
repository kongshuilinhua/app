"""Business projections, seeds and private memory/legacy storage data.

Online business access uses data_access.DataTransaction. Store is only used by the
memory adapter, legacy schema migration and isolated test fixtures.
"""
from __future__ import annotations

import base64
import binascii
import hashlib
import hmac
import os
import threading
from dataclasses import dataclass, field
from datetime import datetime, timezone
from functools import lru_cache
from typing import Any

# 仅开发演示种子密码；正式账号由管理接口创建并单独散列。
DEV_PASSWORD = "12345678"

# 开发机默认强度；真实部署应结合硬件提高并支持轮换。
PBKDF2_ITERATIONS = 120_000
_SALT_BYTES = 16


def utcnow() -> datetime:
    return datetime.now(timezone.utc)


def _seed_time() -> str:
    return utcnow().isoformat().replace("+00:00", "Z")


# ---------------- 密码散列 ----------------

def hash_password(password: str, *, iterations: int = PBKDF2_ITERATIONS) -> str:
    """PBKDF2-HMAC-SHA256，返回 `pbkdf2_sha256$迭代$盐$摘要`（Base64）。"""
    salt = os.urandom(_SALT_BYTES)
    digest = hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), salt, iterations)
    return "pbkdf2_sha256${}${}${}".format(
        iterations,
        base64.b64encode(salt).decode("ascii"),
        base64.b64encode(digest).decode("ascii"),
    )


def verify_password(password: str, stored: str | None) -> bool:
    """常量时间校验；格式不符一律 False，不抛异常。"""
    if not stored:
        return False
    parts = stored.split("$")
    if len(parts) != 4 or parts[0] != "pbkdf2_sha256":
        return False
    try:
        iterations = int(parts[1])
        salt = base64.b64decode(parts[2])
        expected = base64.b64decode(parts[3])
    except (ValueError, binascii.Error):
        return False
    digest = hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), salt, iterations)
    return hmac.compare_digest(digest, expected)


# ---------------- 事务内视图 ----------------

@dataclass
class Store:
    """Private memory/legacy fixture; never returned by production transactions."""

    # 账号：内部字典含 password_hash，只允许经 public_user 投影后对外返回。
    users: list[dict[str, Any]] = field(default_factory=list)
    # 字典项：全部历史项都保留，供页面展示，用 is_active 限制新选择。
    cultivars: list[dict[str, Any]] = field(default_factory=list)
    classes: list[dict[str, Any]] = field(default_factory=list)
    taxonomy_version: int = 1
    policy_version: int = 1
    # 会话：键为令牌（内存）或令牌摘要（数据库加载后），记录不含原始 token。
    sessions: dict[str, dict[str, Any]] = field(default_factory=dict)
    uploads: dict[str, dict[str, Any]] = field(default_factory=dict)
    samples: dict[str, dict[str, Any]] = field(default_factory=dict)
    batches: dict[str, dict[str, Any]] = field(default_factory=dict)
    questions: dict[str, dict[str, Any]] = field(default_factory=dict)
    phone_identities: dict[str, dict[str, Any]] = field(default_factory=dict)
    sms_states: dict[str, dict[str, Any]] = field(default_factory=dict)
    # 原图字节不进入元数据存储，保留字段仅供内存模式与文件模块过渡使用。
    image_bytes: dict[str, bytes] = field(default_factory=dict)
    exports: dict[str, dict[str, Any]] = field(default_factory=dict)
    idempotency: dict[str, Any] = field(default_factory=dict)
    lock: threading.RLock = field(default_factory=threading.RLock)
    # 适配器簿记：实体 -> {记录键: 版本号}，实体 -> {记录键: 规范化 JSON}。
    _revs: dict[str, dict[str, int]] = field(default_factory=dict, repr=False)
    _loaded: dict[str, dict[str, str]] = field(default_factory=dict, repr=False)


# ---------------- 开发种子 ----------------

def _user(user_id: str, username: str, display_name: str, role: str, password_hash: str) -> dict[str, Any]:
    return {
        "user_id": user_id,
        "username": username,
        "display_name": display_name,
        "role": role,
        "is_active": True,
        "password_hash": password_hash,
        "created_at": _seed_time(),
        "last_login_at": None,
    }


@lru_cache(maxsize=1)
def _dev_password_hash() -> str:
    """五个种子账号共用一次散列计算，避免每次启动/建 app 重复 PBKDF2。"""
    return hash_password(DEV_PASSWORD)


def make_user(user_id: str, username: str, display_name: str, role: str, password_hash: str) -> dict[str, Any]:
    return _user(user_id, username, display_name, role, password_hash)


def dev_users() -> list[dict[str, Any]]:
    password_hash = _dev_password_hash()
    return [
        _user("019a0000-0000-7000-8000-000000000001", "super", "超级管理员", "super_admin", password_hash),
        _user("019a0000-0000-7000-8000-000000000002", "admin", "管理员", "admin", password_hash),
        _user("019a0000-0000-7000-8000-000000000003", "collector1", "采集员甲", "collector", password_hash),
        _user("019a0000-0000-7000-8000-000000000004", "collector2", "采集员乙", "collector", password_hash),
        _user("019a0000-0000-7000-8000-000000000005", "expert", "专家", "expert", password_hash),
    ]


def default_cultivars() -> list[dict[str, Any]]:
    """开发种子：正式品种清单尚未取得。"""
    return [
        {"cultivar_id": "unknown", "name": "不确定", "is_active": True, "version": 1},
        {"cultivar_id": "huyou", "name": "胡柚", "is_active": True, "version": 1},
        {"cultivar_id": "xiangyou", "name": "香柚", "is_active": True, "version": 1},
    ]


def default_classes() -> list[dict[str, Any]]:
    """开发种子：citrus_canker 仅为非占位示例，正式类别清单待外部输入。"""
    return [
        {
            "class_id": "unknown",
            "name": "不确定",
            "parent_id": None,
            "is_active": True,
            "is_provisional": False,
            "introduced_taxonomy_version": 1,
            "deprecated_taxonomy_version": None,
            "version": 1,
        },
        {
            "class_id": "citrus_canker",
            "name": "溃疡病",
            "parent_id": None,
            "is_active": True,
            "is_provisional": False,
            "introduced_taxonomy_version": 1,
            "deprecated_taxonomy_version": None,
            "version": 1,
        },
    ]


def build_store() -> Store:
    """内存模式/独立测试用的已播种视图。"""
    return Store(
        users=dev_users(),
        cultivars=default_cultivars(),
        classes=default_classes(),
        taxonomy_version=1,
        policy_version=1,
    )


# ---------------- 对外投影 ----------------

def public_user(user: dict[str, Any]) -> dict[str, Any]:
    """对外账号投影：不含 password_hash 与内部字段。"""
    return {
        "user_id": user["user_id"],
        "username": user["username"],
        "display_name": user["display_name"],
        "role": user["role"],
    }


def public_cultivar(row: dict[str, Any]) -> dict[str, Any]:
    return {
        "cultivar_id": row["cultivar_id"],
        "name": row["name"],
        "is_active": row["is_active"],
        "version": row["version"],
    }


def public_class(row: dict[str, Any]) -> dict[str, Any]:
    return {
        "class_id": row["class_id"],
        "name": row["name"],
        "parent_id": row["parent_id"],
        "is_active": row["is_active"],
        "is_provisional": row["is_provisional"],
        "introduced_taxonomy_version": row["introduced_taxonomy_version"],
        "deprecated_taxonomy_version": row["deprecated_taxonomy_version"],
        "version": row["version"],
    }
