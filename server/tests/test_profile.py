import pytest
from fastapi.testclient import TestClient
from sqlalchemy import create_engine,text,inspect
from types import SimpleNamespace
from server.app.main import create_app
from server.app.phone_auth import _key

PHONE='13800002468'

class Provider:
    def ensure_ready(self):pass
    def send(self,*args):pass
    def verify(self,*args):return args[-1]=='123456'

@pytest.fixture(params=['memory','sqlite'])
def setup(request,tmp_path):
    url='memory' if request.param=='memory' else f'sqlite:///{tmp_path}/data.db'
    app=create_app(url,storage_root=tmp_path,sms_provider=Provider())
    with TestClient(app) as c:yield app,c

def login(c,name='collector1'):
    r=c.post('/api/v1/auth/login',json={'username':name,'password':'12345678'});assert r.status_code==200
    return {'Authorization':'Bearer '+r.json()['access_token']}

def test_profile_auth_owner_and_nickname_only(setup):
    app,c=setup
    assert c.get('/api/v1/me/profile').status_code==401
    assert c.patch('/api/v1/me/profile',json={'display_name':'橘园观察员'}).status_code==401
    h=login(c);old=c.get('/api/v1/me/profile',headers=h).json()
    assert old['phone_masked'] is None and old['phone_verified'] is False
    r=c.patch('/api/v1/me/profile',headers=h,json={'display_name':'  橘园观察员  '});assert r.status_code==200,r.text
    new=r.json();assert new['display_name']=='橘园观察员'
    assert new['user_id']==old['user_id'] and new['username']==old['username'] and new['role']==old['role']
    assert c.get('/api/v1/me',headers=h).json()['display_name']=='橘园观察员'
    other=c.get('/api/v1/me/profile',headers=login(c,'collector2')).json()
    assert other['display_name']!='橘园观察员'
    for field,value in [('role','super_admin'),('username','stolen'),('user_id',other['user_id']),('phone_masked',PHONE)]:
        assert c.patch('/api/v1/me/profile',headers=h,json={'display_name':'bad',field:value}).status_code==422
    assert c.get('/api/v1/me/profile',headers=h).json()['display_name']=='橘园观察员'


def test_nickname_validation_and_disabled_user(setup):
    app,c=setup;h=login(c)
    for bad in ['', ' ', 'a', 'x'*19, 'a\nb','a\x00b','a\x7fb']:
        assert c.patch('/api/v1/me/profile',headers=h,json={'display_name':bad}).status_code==422
    uid=c.get('/api/v1/me',headers=h).json()['user_id']
    with app.state.storage.transaction() as s:s.get_user(uid)['is_active']=False
    assert c.patch('/api/v1/me/profile',headers=h,json={'display_name':'橘园观察员'}).status_code==403


def test_verified_phone_mask_and_nickname_survive_sms_relogin(setup):
    app,c=setup
    proof=c.post('/api/v1/auth/phone/code',json={'phone':PHONE}).json()
    r=c.post('/api/v1/auth/phone/login',json={'phone':PHONE,'challenge_id':proof['challenge_id'],'code':'123456'})
    assert r.status_code==200
    h={'Authorization':'Bearer '+r.json()['access_token']}
    profile=c.get('/api/v1/me/profile',headers=h).json()
    assert profile['phone_masked']=='138****2468' and profile['phone_verified'] is True
    assert PHONE not in str(profile) and 'phone_key' not in profile
    c.patch('/api/v1/me/profile',headers=h,json={'display_name':'青柚观察员'}).raise_for_status()
    # Existing phone identity already has a mask and preserves user identity/name.
    with app.state.storage.transaction() as s:
        s.get_sms_state(_key(SimpleNamespace(app=app),'phone:'+PHONE))['data']['last_send']=0
    proof=c.post('/api/v1/auth/phone/code',json={'phone':PHONE}).json()
    r=c.post('/api/v1/auth/phone/login',json={'phone':PHONE,'challenge_id':proof['challenge_id'],'code':'123456'})
    assert r.json()['user']['user_id']==profile['user_id'] and r.json()['user']['display_name']=='青柚观察员'


def test_schema4_upgrade_phone_mask_and_records_preserved(tmp_path):
    url=f'sqlite:///{tmp_path}/data.db';app=create_app(url,storage_root=tmp_path,sms_provider=Provider())
    with TestClient(app) as c:
        h=login(c);uid=c.get('/api/v1/me',headers=h).json()['user_id']
        with app.state.storage.transaction() as s:
            s.add_phone_identity({'phone_key':_key(SimpleNamespace(app=app),'phone:'+PHONE),'user_id':uid,'masked_phone':None})
        c.patch('/api/v1/me/profile',headers=h,json={'display_name':'升级前昵称'}).raise_for_status()
    engine=create_engine(url)
    with engine.begin() as connection:
        connection.execute(text('ALTER TABLE phone_identities DROP COLUMN masked_phone'))
        connection.execute(text("UPDATE meta_schema SET value='4' WHERE name='schema_version'"))
    app=create_app(url,storage_root=tmp_path,sms_provider=Provider())
    with TestClient(app) as c:
        h=login(c);p=c.get('/api/v1/me/profile',headers=h).json()
        assert p['user_id']==uid and p['display_name']=='升级前昵称' and p['phone_verified'] and p['phone_masked'] is None
        proof=c.post('/api/v1/auth/phone/code',json={'phone':PHONE}).json()
        r=c.post('/api/v1/auth/phone/login',json={'phone':PHONE,'challenge_id':proof['challenge_id'],'code':'123456'})
        assert r.json()['user']['user_id']==uid
        assert c.get('/api/v1/me/profile',headers=h).json()['phone_masked']=='138****2468'
    assert 'masked_phone' in {x['name'] for x in inspect(engine).get_columns('phone_identities')}
    engine.dispose()
