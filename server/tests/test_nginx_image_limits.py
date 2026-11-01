"""Optional real-Nginx boundary regression; runs only with an explicit local binary."""
import argparse
import http.server
import importlib.util
import json
import os
from pathlib import Path
import socket
import subprocess
import threading
import time
import httpx
import pytest

@pytest.mark.skipif(not os.getenv('TEST_NGINX_EXE'), reason='set TEST_NGINX_EXE to a local Nginx binary')
def test_binary_image_routes_and_json_limits(tmp_path):
    binary=Path(os.environ['TEST_NGINX_EXE']).resolve()
    assert binary.is_file()
    class Echo(http.server.BaseHTTPRequestHandler):
        protocol_version='HTTP/1.1'
        def do_POST(self):
            n=int(self.headers.get('Content-Length','0'))
            content=self.rfile.read(n)
            result=json.dumps({'received':len(content)}).encode()
            self.send_response(200);self.send_header('Content-Length',str(len(result)));self.end_headers();self.wfile.write(result)
        def log_message(self,*args):pass
    upstream=http.server.ThreadingHTTPServer(('127.0.0.1',0),Echo)
    thread=threading.Thread(target=upstream.serve_forever,daemon=True);thread.start()
    with socket.socket() as probe:
        probe.bind(('127.0.0.1',0));port=probe.getsockname()[1]
    root=Path(__file__).resolve().parents[2]
    spec=importlib.util.spec_from_file_location('nginx_render_test',root/'deploy/nginx/render.py')
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
    frontend=tmp_path/'frontend';frontend.mkdir();(frontend/'assets').mkdir();(frontend/'index.html').write_text('gateway-test')
    runtime=tmp_path/'runtime'
    module.render(argparse.Namespace(mode='local',output=runtime,frontend=frontend,upstream=f'127.0.0.1:{upstream.server_port}',port=port))
    prefix=runtime.as_posix()+'/'
    flags=getattr(subprocess,'CREATE_NO_WINDOW',0)
    with open(tmp_path/'nginx-launch.log','wb') as log:
        proc=subprocess.Popen([str(binary),'-p',prefix,'-c','nginx.conf','-g','daemon off;'],cwd=runtime,stdout=log,stderr=log,creationflags=flags)
        try:
            with httpx.Client(base_url=f'http://127.0.0.1:{port}',timeout=20,trust_env=False) as c:
                deadline=time.monotonic()+10
                while True:
                    assert proc.poll() is None,'test Nginx exited during startup'
                    try:
                        if c.get('/').status_code==200:break
                    except httpx.TransportError:pass
                    assert time.monotonic()<deadline,'test Nginx did not start'
                    time.sleep(.05)
                image=b'x'*(2*1024*1024)
                for path in ['/api/v1/recognitions','/api/v1/diagnoses','/api/v1/recognitions/','/api/v1/diagnoses/','/api/v1/uploads/00000000-0000-7000-8000-000000000001/images/00000000-0000-7000-8000-000000000002']:
                    r=c.post(path,content=image);assert r.status_code==200,(path,r.status_code)
                    assert r.json()['received']==len(image)
                exact=c.post('/api/v1/recognitions',content=b'x'*(20*1024*1024));assert exact.status_code==200
                assert c.post('/api/v1/diagnoses',content=b'x'*(20*1024*1024+1)).status_code==413
                for path in ['/api/v1/diagnoses/advice','/api/v1/auth/login','/api/v1/recognitions/extra']:
                    assert c.post(path,content=b'x'*(1024*1024+1)).status_code==413,path
        finally:
            subprocess.run([str(binary),'-p',prefix,'-c','nginx.conf','-s','quit'],cwd=runtime,stdout=log,stderr=log,creationflags=flags,timeout=10)
            try:proc.wait(timeout=10)
            finally:
                upstream.shutdown();upstream.server_close()
