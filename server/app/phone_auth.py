"""PNVS phone authentication; codes are generated and checked only by Aliyun."""
from __future__ import annotations
import hashlib
import hmac
import json
import logging
import os
import re
import secrets
import time
from fastapi import APIRouter, Request
from pydantic import BaseModel, ConfigDict, Field, SecretStr, field_validator
from .auth import LoginResponse, AuthUserOut
from .common import DomainError, create_session, hash_password, iso_utc, new_uuid7
from .state import make_user, public_user

router = APIRouter(prefix='/api/v1/auth/phone', tags=['auth'])
TTL = 300
logger = logging.getLogger(__name__)


def unavailable():
    return DomainError(503, 'SMS_UNAVAILABLE', '短信服务暂不可用，请使用账号密码登录或稍后重试')


class AliyunSmsProvider:
    def ensure_ready(self):
        if os.getenv('APP_SMS_ENABLED', '').lower() != 'true' or not all(os.getenv(k) for k in (
            'ALIBABA_CLOUD_ACCESS_KEY_ID', 'ALIBABA_CLOUD_ACCESS_KEY_SECRET', 'APP_SMS_SIGN_NAME', 'APP_SMS_TEMPLATE_CODE')):
            raise unavailable()
        try:
            import alibabacloud_dypnsapi20170525.client
        except ImportError:
            raise unavailable() from None

    def _call(self, action, **kwargs):
        self.ensure_ready()
        try:
            from alibabacloud_dypnsapi20170525.client import Client
            from alibabacloud_dypnsapi20170525 import models
            from alibabacloud_tea_openapi.models import Config
            from alibabacloud_tea_util.models import RuntimeOptions
            config = Config(access_key_id=os.environ['ALIBABA_CLOUD_ACCESS_KEY_ID'],
                            access_key_secret=os.environ['ALIBABA_CLOUD_ACCESS_KEY_SECRET'],
                            security_token=os.getenv('ALIBABA_CLOUD_SECURITY_TOKEN'))
            config.endpoint = 'dypnsapi.aliyuncs.com'
            client = Client(config)
            runtime = RuntimeOptions(connect_timeout=5000, read_timeout=10000, autoretry=False)
            if action == 'send':
                response = client.send_sms_verify_code_with_options(models.SendSmsVerifyCodeRequest(**kwargs), runtime)
            else:
                response = client.check_sms_verify_code_with_options(models.CheckSmsVerifyCodeRequest(**kwargs), runtime)
            data = response.body.to_map()
            if data.get('Code') != 'OK' or data.get('Success') is not True:
                code = str(data.get('Code', 'unknown'))
                logger.warning('PNVS request rejected: %s', code if re.fullmatch(r'[A-Za-z0-9_.:-]{1,80}', code) else 'unknown')
                raise unavailable()
            return data.get('Model') or {}
        except DomainError:
            raise
        except Exception as exc:
            # SDK errors may contain signed URLs, phone numbers or API keys.
            code = str(getattr(exc, 'code', 'unknown'))
            logger.warning('PNVS request failed: %s [%s]', code if re.fullmatch(r'[A-Za-z0-9_.:-]{1,80}', code) else 'unknown', type(exc).__name__)
            raise unavailable() from None

    def send(self, phone, challenge):
        self._call('send', phone_number=phone, country_code='86',
                   sign_name=os.environ['APP_SMS_SIGN_NAME'], template_code=os.environ['APP_SMS_TEMPLATE_CODE'],
                   template_param=json.dumps({'code': '##code##', 'min': '5'}),
                   scheme_name=os.getenv('APP_SMS_SCHEME_NAME') or None, out_id=challenge, valid_time=TTL, interval=60,
                   code_length=6, code_type=1, duplicate_policy=1, return_verify_code=False, auto_retry=0)

    def verify(self, phone, challenge, code):
        result = self._call('check', phone_number=phone, country_code='86', scheme_name=os.getenv('APP_SMS_SCHEME_NAME') or None,
                            out_id=challenge, verify_code=code)
        return result.get('VerifyResult') == 'PASS'


def normalize_phone(value: str) -> str:
    match = re.fullmatch(r'(?:\+?86)?(1[3-9][0-9]{9})', value.strip())
    if not match:
        raise ValueError('请输入有效的中国内地手机号')
    return match.group(1)


class PhoneIn(BaseModel):
    model_config = ConfigDict(extra='forbid')
    phone: str = Field(max_length=16)

    @field_validator('phone')
    @classmethod
    def normalize(cls, value):
        return normalize_phone(value)


class PhoneProof(PhoneIn):
    challenge_id: str = Field(min_length=32, max_length=100)
    code: SecretStr

    @field_validator('code')
    @classmethod
    def digits(cls, value):
        if not re.fullmatch(r'[0-9]{6}', value.get_secret_value()):
            raise ValueError('请输入6位数字验证码')
        return value


class SendOut(BaseModel):
    challenge_id: str
    resend_after_seconds: int = 60
    expires_in: int = TTL


def _key(request, value):
    secret = os.getenv('APP_SMS_IDENTITY_SECRET')
    key = secret.encode() if secret else request.app.state.diagnosis_key
    return hmac.new(key, value.encode(), hashlib.sha256).hexdigest()


def _state(store, key):
    row = store.get_sms_state(key)
    if row is None:
        row = {'record_key': key, 'data': {}}
        store.add_sms_state(row)
    return row['data']


def _rate():
    return DomainError(429, 'SMS_RATE_LIMITED', '请求过于频繁，请稍后重试', {'retry_after_seconds': 60})


def _invalid():
    return DomainError(401, 'SMS_CODE_INVALID', '验证码无效、已使用或已过期，请重新获取')


@router.post('/code', response_model=SendOut, summary='发送手机登录验证码')
def send_code(payload: PhoneIn, request: Request):
    provider = request.app.state.sms_provider
    provider.ensure_ready()
    now = int(time.time())
    pk = _key(request, 'phone:' + payload.phone)
    challenge = secrets.token_urlsafe(32)
    # Forwarded headers are intentionally ignored: only the trusted peer is used.
    ip = request.client.host if request.client else 'unknown'
    with request.app.state.storage.transaction() as store:
        global_state = _state(store, _key(request, 'sms-global'))
        ip_state = _state(store, _key(request, 'ip:' + ip))
        phone_state = _state(store, pk)
        checks = [(global_state, now // 86400, 100), (ip_state, now // 3600, 10),
                  (phone_state, now // 86400, 10)]
        if now - phone_state.get('last_send', 0) < 60:
            raise _rate()
        if any(s.get('bucket') == bucket and s.get('count', 0) >= limit for s, bucket, limit in checks):
            raise _rate()
        for s, bucket, _ in checks:
            s['count'] = s.get('count', 0) + 1 if s.get('bucket') == bucket else 1
            s['bucket'] = bucket
        phone_state.update(last_send=now, challenge=hashlib.sha256(challenge.encode()).hexdigest(),
                           expires=now + TTL, status='pending', attempts=0)
    try:
        provider.send(payload.phone, challenge)
    except Exception:
        # Keep the reserved budget even after a network timeout: the SMS may have been sent.
        raise unavailable() from None
    with request.app.state.storage.transaction() as store:
        state = store.get_sms_state(pk)['data']
        if hmac.compare_digest(state['challenge'], hashlib.sha256(challenge.encode()).hexdigest()):
            state['status'] = 'sent'
    return SendOut(challenge_id=challenge)


def _session(store, user):
    session = create_session(store, user)
    user['last_login_at'] = iso_utc()
    return LoginResponse(access_token=session['access_token'], refresh_token=session['refresh_token'],
                         expires_in=session['expires_in'], user=AuthUserOut(**public_user(user)))


def _apply_verified(payload, request, operation):
    provider = request.app.state.sms_provider
    provider.ensure_ready()
    pk = _key(request, 'phone:' + payload.phone)
    digest = hashlib.sha256(payload.challenge_id.encode()).hexdigest()
    with request.app.state.storage.transaction() as store:
        row = store.get_sms_state(pk)
        state = row['data'] if row else {}
        if (state.get('status') != 'sent' or state.get('expires', 0) <= time.time()
                or state.get('attempts', 0) >= 5
                or not hmac.compare_digest(state.get('challenge', ''), digest)):
            raise _invalid()
        state['attempts'] += 1
        state['status'] = 'checking'
    try:
        passed = provider.verify(payload.phone, payload.challenge_id, payload.code.get_secret_value())
    except Exception:
        with request.app.state.storage.transaction() as store:
            state = store.get_sms_state(pk)['data']
            if state.get('challenge') == digest:
                state['status'] = 'sent'
        raise unavailable() from None
    error = None
    result = None
    with request.app.state.storage.transaction() as store:
        state = store.get_sms_state(pk)['data']
        if state.get('challenge') != digest or state.get('status') != 'checking' or state['expires'] <= time.time():
            raise _invalid()
        state['status'] = 'consumed' if passed else 'sent'
        if not passed:
            error = _invalid()
        else:
            # Commit consumption even on a business rejection; no successful proof is reusable.
            result, error = operation(store, pk)
    if error:
        raise error
    return result


def _user_for_phone(store, pk):
    identity = store.get_phone_identity(pk)
    return store.get_user(identity['user_id']) if identity else None


@router.post('/login', response_model=LoginResponse, summary='手机验证码登录，首次验证后自动创建账号')
def phone_login(payload: PhoneProof, request: Request):
    def operation(store, pk):
        user = _user_for_phone(store, pk)
        if user is not None and not user.get('is_active', False):
            return None, DomainError(403, 'ACCOUNT_DISABLED', '账号已停用')
        if user is None:
            user = make_user(new_uuid7(), 'sms_' + secrets.token_hex(10), '用户' + payload.phone[-4:],
                             'collector', hash_password(secrets.token_urlsafe(48)))
            store.add_user(user)
            store.add_phone_identity({'phone_key': pk, 'user_id': user['user_id'],
                                      'masked_phone': payload.phone[:3] + '****' + payload.phone[-4:]})
        identity = store.get_phone_identity(pk)
        identity['masked_phone'] = payload.phone[:3] + '****' + payload.phone[-4:]
        return _session(store, user), None
    return _apply_verified(payload, request, operation)
