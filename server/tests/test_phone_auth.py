import concurrent.futures
from types import SimpleNamespace
import pytest
from fastapi.testclient import TestClient
from server.app.main import create_app
from server.app import phone_auth

PHONE = '13800000000'

class Provider:
    def __init__(self): self.sent = []; self.checks = []; self.fail = False
    def ensure_ready(self): pass
    def send(self, phone, challenge):
        self.sent.append((phone, challenge))
        if self.fail: raise RuntimeError('must never expose SDK details')
    def verify(self, phone, challenge, code):
        self.checks.append((phone, challenge, code))
        return (phone, challenge) in self.sent and code == '123456'

@pytest.fixture(params=['memory', 'sqlite'])
def setup(request, tmp_path):
    provider = Provider()
    url = 'memory' if request.param == 'memory' else f'sqlite:///{tmp_path}/meta.db'
    app = create_app(url, storage_root=tmp_path, sms_provider=provider)
    with TestClient(app) as client:
        yield app,client,provider


def send(c, phone=PHONE):
    r=c.post('/api/v1/auth/phone/code',json={'phone':phone}); assert r.status_code==200,r.text
    assert set(r.json()) == {'challenge_id','resend_after_seconds','expires_in'}
    return dict(phone=phone,challenge_id=r.json()['challenge_id'],code='123456')


def test_login_role_refresh_replay_and_privacy(setup):
    app,c,p=setup; data=send(c,'+86'+PHONE)
    assert p.sent[0][0]==PHONE
    r=c.post('/api/v1/auth/phone/login',json=data);assert r.status_code==200,r.text
    account=r.json();assert account['user']['role']=='collector'
    assert PHONE not in r.text
    assert c.post('/api/v1/auth/refresh',json={'refresh_token':account['refresh_token']}).status_code==200
    assert c.post('/api/v1/auth/phone/login',json=data).status_code==401
    assert c.post('/api/v1/auth/phone/code',json={'phone':PHONE}).status_code==429
    with app.state.storage.transaction(write=False) as s:
        assert s.get_phone_identity(phone_auth._key(SimpleNamespace(app=app), 'phone:'+PHONE))['user_id']==account['user']['user_id']


def test_five_attempts_commit_and_block_even_valid_code(setup):
    _,c,p=setup; data=send(c)
    for _ in range(5):
        assert c.post('/api/v1/auth/phone/login',json={**data,'code':'000000'}).status_code==401
    assert c.post('/api/v1/auth/phone/login',json=data).status_code==401
    assert len(p.checks)==5



def test_expiry_resend_and_disabled_account(setup, monkeypatch):
    app,c,p=setup;now=[2000000000];monkeypatch.setattr(phone_auth.time,'time',lambda:now[0])
    first=send(c);now[0]+=61;second=send(c)
    assert c.post('/api/v1/auth/phone/login',json=first).status_code==401
    r=c.post('/api/v1/auth/phone/login',json=second);assert r.status_code==200
    with app.state.storage.transaction() as s:s.get_user(r.json()['user']['user_id'])['is_active']=False
    now[0]+=61;third=send(c)
    assert c.post('/api/v1/auth/phone/login',json=third).status_code==403
    now[0]+=61;expired=send(c);now[0]+=301
    assert c.post('/api/v1/auth/phone/login',json=expired).status_code==401


def test_server_budget_and_provider_failures(setup, monkeypatch):
    _,c,p=setup;p.fail=True
    assert c.post('/api/v1/auth/phone/code',json={'phone':PHONE}).status_code==503
    assert c.post('/api/v1/auth/phone/code',json={'phone':PHONE}).status_code==429
    for index in range(9):
        c.post('/api/v1/auth/phone/code',json={'phone':f'139000000{index:02d}'})
    assert len(p.sent)==10
    assert c.post('/api/v1/auth/phone/code',json={'phone':'13700000000'}).status_code==429


def test_concurrent_code_consumed_once(setup):
    _,c,p=setup;data=send(c)
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        statuses=list(pool.map(lambda _:c.post('/api/v1/auth/phone/login',json=data).status_code,range(4)))
    assert statuses.count(200)==1,statuses
    assert set(statuses)<={200,401,409}


def test_disabled_config_and_validation(tmp_path,monkeypatch):
    monkeypatch.delenv('APP_SMS_ENABLED',raising=False)
    with TestClient(create_app('memory',storage_root=tmp_path)) as c:
        assert c.post('/api/v1/auth/phone/code',json={'phone':PHONE}).status_code==503
        for bad in ['x','', '123', '١٣٨٠٠٠٠٠٠٠٠']:
            assert c.post('/api/v1/auth/phone/code',json={'phone':bad}).status_code==422
        assert c.post('/api/v1/auth/phone/code',json={'phone':PHONE,'role':'super_admin'}).status_code==422


def test_sdk_parameters_and_pass_is_required(monkeypatch):
    from alibabacloud_dypnsapi20170525.client import Client
    for key,value in {'APP_SMS_ENABLED':'true','ALIBABA_CLOUD_ACCESS_KEY_ID':'id',
                      'ALIBABA_CLOUD_ACCESS_KEY_SECRET':'secret','APP_SMS_SIGN_NAME':'test','APP_SMS_TEMPLATE_CODE':'100001'}.items():
        monkeypatch.setenv(key,value)
    seen=[]
    def response(model):return SimpleNamespace(body=SimpleNamespace(to_map=lambda:{'Code':'OK','Success':True,'Model':model}))
    def send(self,request,runtime):seen.append(request.to_map());return response({'BizId':'id'})
    monkeypatch.setattr(Client,'send_sms_verify_code_with_options',send)
    monkeypatch.setattr(Client,'check_sms_verify_code_with_options',lambda *args:response({'VerifyResult':'UNKNOWN'}))
    p=phone_auth.AliyunSmsProvider();p.send(PHONE,'challenge')
    assert seen[0]['ReturnVerifyCode'] is False and seen[0]['DuplicatePolicy']==1
    assert seen[0]['TemplateParam']=='{"code": "##code##", "min": "5"}'
    assert p.verify(PHONE,'challenge','123456') is False


def test_phone_identity_and_challenge_persist_on_restart(tmp_path,monkeypatch):
    provider=Provider();url=f'sqlite:///{tmp_path}/data.db'
    now=[2000000000];monkeypatch.setattr(phone_auth.time,'time',lambda:now[0])
    with TestClient(create_app(url,storage_root=tmp_path,sms_provider=provider)) as c:data=send(c)
    with TestClient(create_app(url,storage_root=tmp_path,sms_provider=provider)) as c:
        r=c.post('/api/v1/auth/phone/login',json=data);assert r.status_code==200;uid=r.json()['user']['user_id']
    now[0]+=61
    with TestClient(create_app(url,storage_root=tmp_path,sms_provider=provider)) as c:
        data=send(c);r=c.post('/api/v1/auth/phone/login',json=data)
        assert r.status_code==200 and r.json()['user']['user_id']==uid
