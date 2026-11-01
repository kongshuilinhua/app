"""认证与配置接口：login / refresh / logout / me / config.bootstrap。

元数据通过 storage.transaction() 读写，事务只在请求处理期间短暂持有；
密码使用 PBKDF2 散列校验，令牌为不透明随机串且只保存摘要。
"""
from __future__ import annotations

import re
from typing import Literal

from fastapi import APIRouter, Depends, Request, Response
from pydantic import BaseModel, ConfigDict, Field, field_validator

from .common import (
    DomainError,
    create_session,
    current_user,
    hash_password,
    iso_utc,
    require_roles,
    revoke_session,
    session_key,
    utcnow,
    verify_password,
)
from .state import public_class, public_cultivar, public_user

router = APIRouter(prefix="/api/v1")

# 账号不存在时仍做一次同代价的散列校验，避免用响应时间区分账号是否存在。
_DUMMY_PASSWORD_HASH = hash_password("not-a-real-password")


# ---------------- 响应模型（字段与 OpenAPI 一致） ----------------

class LoginRequest(BaseModel):
    username: str
    password: str


class AuthUserOut(BaseModel):
    user_id: str
    username: str
    display_name: str
    role: str


class LoginResponse(BaseModel):
    access_token: str
    refresh_token: str
    token_type: Literal["Bearer"] = "Bearer"
    expires_in: int
    user: AuthUserOut


class RefreshRequest(BaseModel):
    refresh_token: str


class RefreshResponse(BaseModel):
    access_token: str
    refresh_token: str
    token_type: Literal["Bearer"] = "Bearer"
    expires_in: int


class MeResponse(BaseModel):
    user_id: str
    username: str
    display_name: str
    role: str


class ProfileResponse(MeResponse):
    phone_masked: str | None = None
    phone_verified: bool = False


class ProfilePatch(BaseModel):
    model_config = ConfigDict(extra='forbid')
    display_name: str

    @field_validator('display_name')
    @classmethod
    def nickname(cls, value):
        value = value.strip()
        if not 2 <= len(value) <= 18 or any(ord(c) < 32 or 127 <= ord(c) <= 159 for c in value):
            raise ValueError('昵称须为2至18个字符，不能包含控制字符')
        return value


def _profile(store, user):
    identity = store.find_phone_for_user(user['user_id'])
    masked = identity.get('masked_phone') if identity else None
    if not isinstance(masked, str) or not re.fullmatch(r'1[3-9][0-9]\*{4}[0-9]{4}', masked):
        masked = None
    return ProfileResponse(**public_user(user), phone_masked=masked, phone_verified=identity is not None)


class CultivarOut(BaseModel):
    cultivar_id: str
    name: str
    is_active: bool
    version: int


class ClassOut(BaseModel):
    class_id: str
    name: str
    parent_id: str | None = None
    is_active: bool
    is_provisional: bool
    introduced_taxonomy_version: int
    deprecated_taxonomy_version: int | None = None
    version: int


class BootstrapResponse(BaseModel):
    cultivars: list[CultivarOut]
    classes: list[ClassOut]
    taxonomy_version: int
    policy_version: int
    server_time: str = Field(description="UTC，带时区")


# ---------------- 接口 ----------------

@router.post("/auth/login", tags=["auth"], response_model=LoginResponse, summary="账号密码登录")
def login(payload: LoginRequest, request: Request) -> LoginResponse:
    storage = request.app.state.storage
    with storage.transaction() as store:
        user = store.find_user(payload.username)
        password_ok = verify_password(payload.password, user["password_hash"] if user else _DUMMY_PASSWORD_HASH)
        if user is None or not password_ok:
            raise DomainError(401, "AUTH_INVALID", "账号或密码错误", {"reason": "bad_credentials"})
        if not user.get("is_active", False):
            raise DomainError(403, "ACCOUNT_DISABLED", "账号已停用")
        session = create_session(store, user)
        user["last_login_at"] = iso_utc()
        viewer = public_user(user)
    return LoginResponse(
        access_token=session["access_token"],
        refresh_token=session["refresh_token"],
        token_type="Bearer",
        expires_in=session["expires_in"],
        user=AuthUserOut(**viewer),
    )


@router.post("/auth/refresh", tags=["auth"], response_model=RefreshResponse, summary="轮换刷新令牌")
def refresh(payload: RefreshRequest, request: Request) -> RefreshResponse:
    storage = request.app.state.storage
    with storage.transaction() as store:
        record = store.get_session(payload.refresh_token)
        # 旧刷新令牌在轮换、logout、停用后一律失效；refresh 过期也只报 revoked，
        # 避免客户端把“刷新已过期”当作可再次刷新的 access 过期。
        if (
            record is None
            or record.get("kind") != "refresh"
            or record.get("revoked")
            or record["expires_at"] <= utcnow()
        ):
            raise DomainError(401, "AUTH_INVALID", "刷新令牌无效或已使用", {"reason": "revoked"})
        user = store.get_user(record["user_id"])
        if user is None:
            raise DomainError(401, "AUTH_INVALID", "刷新令牌无效或已使用", {"reason": "revoked"})
        if not user.get("is_active", False):
            raise DomainError(403, "ACCOUNT_DISABLED", "账号已停用")
        revoke_session(store, record["session_id"])
        session = create_session(store, user)
    return RefreshResponse(
        access_token=session["access_token"],
        refresh_token=session["refresh_token"],
        token_type="Bearer",
        expires_in=session["expires_in"],
    )


@router.post("/auth/logout", tags=["auth"], status_code=204, response_model=None, summary="退出并撤销当前会话")
def logout(request: Request, _user: dict = Depends(current_user)) -> Response:
    session_id = getattr(request.state, "session_id", None)
    if session_id:
        with request.app.state.storage.transaction() as store:
            revoke_session(store, session_id)
    return Response(status_code=204)


@router.get("/me", tags=["auth"], response_model=MeResponse, summary="当前账号")
def me(user: dict = Depends(current_user)) -> MeResponse:
    return MeResponse(**user)


@router.get('/me/profile', tags=['auth'], response_model=ProfileResponse, summary='当前用户个人资料')
def get_profile(request: Request, user: dict = Depends(current_user)):
    with request.app.state.storage.transaction(write=False) as store:
        return _profile(store, user)


@router.patch('/me/profile', tags=['auth'], response_model=ProfileResponse, summary='修改当前用户昵称')
def patch_profile(payload: ProfilePatch, request: Request, viewer: dict = Depends(current_user)):
    with request.app.state.storage.transaction() as store:
        user = store.get_user(viewer['user_id'])
        if user is None:
            raise DomainError(401, 'AUTH_INVALID', '登录已失效，请重新登录')
        if not user.get('is_active', False):
            raise DomainError(403, 'ACCOUNT_DISABLED', '账号已停用')
        user['display_name'] = payload.display_name
        return _profile(store, user)


@router.get("/config/bootstrap", tags=["config"], response_model=BootstrapResponse, summary="品种/类别与版本")
def bootstrap(request: Request, _user: dict = Depends(current_user)) -> BootstrapResponse:
    with request.app.state.storage.transaction(write=False) as store:
        response = BootstrapResponse(
            cultivars=[CultivarOut(**public_cultivar(row)) for row in store.list_cultivars()],
            classes=[ClassOut(**public_class(row)) for row in store.list_classes()],
            taxonomy_version=store.get_config()["taxonomy_version"],
            policy_version=store.get_config()["policy_version"],
            server_time=iso_utc(),
        )
    return response


# 后续业务模块可直接 from .auth import current_user, require_roles
__all__ = ["router", "current_user", "require_roles", "BootstrapResponse", "MeResponse"]
