"""Render a standalone Nginx instance; no third-party Python dependencies."""
from __future__ import annotations

import argparse
import os
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def quoted(path: Path) -> str:
    value = path.resolve().as_posix()
    if any(c in value for c in '\r\n\x00$";{}'):
        raise ValueError("Path contains characters unsafe in Nginx configuration")
    return f'"{value}"'


def tcp_port(value: str) -> int:
    number = int(value)
    if not 1 <= number <= 65535:
        raise argparse.ArgumentTypeError("port must be between 1 and 65535")
    return number


def api_location(selector: str, upstream: str, *, image: bool = False, login: bool = False) -> str:
    limits = "limit_req zone=api burst=60 nodelay;"
    if login:
        limits += "\n        limit_req zone=login burst=5 nodelay;"
    return f"""
    location {selector} {{
        client_max_body_size {'20m' if image else '1m'};
        {limits}
        proxy_pass http://{upstream};
    }}
"""


def application_server(frontend: Path, upstream: str, listen: str, name: str, tls: str = "") -> str:
    # All locations inherit the same headers. No static mapping to files outside
    # assets/index.html exists, even when a public URL resembles a storage key.
    routes = api_location("= /api", upstream)
    routes += api_location("= /api/v1/auth/login", upstream, login=True)
    routes += api_location("= /api/v1/auth/phone/code", upstream, login=True)
    routes += api_location("= /api/v1/auth/phone/login", upstream, login=True)
    routes += api_location('~ "^/api/v1/uploads/[0-9a-fA-F-]{36}/images/[0-9a-fA-F-]{36}$"', upstream, image=True)
    # Binary image requests must share the backend 20 MiB bound; advice remains ordinary JSON.
    routes += api_location('~ "^/api/v1/(recognitions|diagnoses)/?$"', upstream, image=True)
    routes += api_location("/api/", upstream)
    errors = ""
    for status, code, message in (
        (403, "GATEWAY_FORBIDDEN", "Access denied"),
        (404, "GATEWAY_NOT_FOUND", "Resource not found"),
        (413, "PAYLOAD_TOO_LARGE", "图片或请求内容过大，请缩小后重试"),
        (429, "RATE_LIMITED", "Too many requests; retry later"),
        (502, "GATEWAY_UNAVAILABLE", "Application is unavailable"),
        (504, "GATEWAY_TIMEOUT", "Application response timed out"),
    ):
        errors += f"""
    error_page {status} = @error_{status};
    location @error_{status} {{
        default_type application/json;
        return {status} '{{"error":{{"code":"{code}","message":"{message}","request_id":"$gateway_request_id"}}}}';
    }}
"""
    return f"""
server {{
    listen {listen};
    server_name {name};
    {tls}
    root {quoted(frontend)};
    client_max_body_size 1m;
    location ~ (^|/)\\. {{ deny all; }}
    {routes}
    location /assets/ {{ try_files $uri =404; }}
    location = /index.html {{ try_files /index.html =404; }}
    location / {{ try_files /index.html =404; }}
    {errors}
}}
"""


def render(args: argparse.Namespace) -> Path:
    output = args.output.resolve()
    frontend = args.frontend.resolve()
    quoted(output)
    quoted(frontend)
    # A runtime directory inside the frontend would publish nginx.conf and
    # logs as static assets; resolve() also unfolds symlinked aliases.
    if output.is_relative_to(frontend):
        raise ValueError("--output must stay outside the frontend directory")
    if not (frontend / "index.html").is_file() or not (frontend / "assets").is_dir():
        raise ValueError("frontend must contain a built index.html and assets directory")
    if not re.fullmatch(r"127\.0\.0\.1:[0-9]{1,5}", args.upstream):
        raise ValueError("upstream must be 127.0.0.1:PORT (API and gateway on the same host)")
    tcp_port(args.upstream.rsplit(":", 1)[1])
    if args.mode == "production":
        if not args.domain or len(args.domain) > 253 or not re.fullmatch(
            r"[a-zA-Z0-9](?:[a-zA-Z0-9-]*[a-zA-Z0-9])?(?:\.[a-zA-Z0-9](?:[a-zA-Z0-9-]*[a-zA-Z0-9])?)+", args.domain
        ):
            raise ValueError("production requires a DNS domain name")
        for path in (args.cert, args.key):
            if path is None or not path.is_absolute() or not path.is_file():
                raise ValueError("production requires existing absolute --cert and --key paths")
            quoted(path)
        acme = (args.acme_root or output / "acme").resolve()
        quoted(acme)
        acme.mkdir(parents=True, exist_ok=True)
        tls = f"""ssl_certificate {quoted(args.cert)};
    ssl_certificate_key {quoted(args.key)};
    ssl_protocols TLSv1.2 TLSv1.3;
    ssl_session_cache shared:TLS:10m;
    ssl_session_tickets off;"""
        servers = f"""
server {{
    listen {args.http_port};
    server_name {args.domain};
    location ^~ /.well-known/acme-challenge/ {{ root {quoted(acme)}; try_files $uri =404; }}
    location / {{ return 308 https://{args.domain}{'' if args.https_port == 443 else ':' + str(args.https_port)}$request_uri; }}
}}
"""
        servers += application_server(frontend, args.upstream, f"{args.https_port} ssl", args.domain, tls)
    else:
        servers = application_server(frontend, args.upstream, f"127.0.0.1:{args.port}", "localhost 127.0.0.1")
    # Official Windows builds also initialize unused FastCGI/uWSGI/SCGI temp
    # subdirectories under temp during nginx -t.
    for folder in ("logs", "client_temp", "proxy_temp", "temp"):
        (output / folder).mkdir(parents=True, exist_ok=True)
    worker_user = ""
    if args.mode == "production" and os.name != "nt":
        if not re.fullmatch(r"[a-z_][a-z0-9_-]*", args.worker_user):
            raise ValueError("invalid Linux worker user")
        worker_user = f"user {args.worker_user};\n"
    config = "# Generated by deploy/nginx/render.py. Re-render rather than editing.\n" + worker_user + r"""
worker_processes 1;
pid logs/nginx.pid;
error_log logs/error.log warn;
events { worker_connections 1024; }
http {
    server_tokens off;
    types {
        text/html html;
        text/css css;
        application/javascript js mjs;
        application/json json;
        image/png png;
        image/jpeg jpg jpeg;
        image/svg+xml svg;
        image/x-icon ico;
        font/woff2 woff2;
    }
    default_type application/octet-stream;
    sendfile on;
    keepalive_timeout 65;
    client_body_timeout 300s;
    send_timeout 300s;
    client_body_temp_path client_temp;
    proxy_temp_path proxy_temp;
    fastcgi_temp_path temp/fastcgi;
    uwsgi_temp_path temp/uwsgi;
    scgi_temp_path temp/scgi;
    map $request_id $gateway_request_id {
        "~^(.{8})(.{4})(.{4})(.{4})(.{12})$" $1-$2-$3-$4-$5;
        default $request_id;
    }
    map $uri $route_cache {
        default no-cache;
        ~^/api(?:/|$) no-store;
        ~^/assets/ "public, max-age=31536000, immutable";
    }
    map $status $response_cache {
        default no-store;
        ~^[23] $route_cache;
    }
    # Log no query string, Authorization, cookies or request body.
    log_format gateway escape=json '{"time":"$time_iso8601","client":"$remote_addr",'
        '"method":"$request_method","uri":"$uri","status":$status,'
        '"bytes":$body_bytes_sent,"seconds":$request_time,'
        '"upstream_status":"$upstream_status","request_id":"$gateway_request_id"}';
    access_log logs/access.log gateway;
    add_header X-Request-Id $gateway_request_id always;
    add_header Cache-Control $response_cache always;
    add_header X-Content-Type-Options nosniff always;
    add_header X-Frame-Options DENY always;
    add_header Referrer-Policy same-origin always;
    limit_req_zone $binary_remote_addr zone=api:10m rate=30r/s;
    limit_req_zone $binary_remote_addr zone=login:1m rate=5r/m;
    limit_req_status 429;
    proxy_http_version 1.1;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $remote_addr;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_set_header X-Forwarded-Host $host;
    proxy_set_header X-Forwarded-Port $server_port;
    proxy_set_header Forwarded "";
    proxy_set_header Connection "";
    proxy_set_header X-Request-Id $gateway_request_id;
    proxy_hide_header X-Request-Id;
    proxy_hide_header Cache-Control;
    proxy_connect_timeout 5s;
    proxy_send_timeout 300s;
    proxy_read_timeout 120s;
    proxy_buffering off;
    proxy_intercept_errors off;
    proxy_next_upstream off;
""" + servers + "\n}\n"
    target = output / "nginx.conf"
    temp = output / "nginx.conf.new"
    temp.write_bytes(config.encode("utf-8"))
    temp.replace(target)
    return target


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mode", choices=("local", "production"), default="local")
    parser.add_argument("--output", type=Path, default=ROOT / ".runtime/gateway")
    parser.add_argument("--frontend", type=Path, default=ROOT / "admin/dist")
    parser.add_argument("--upstream", default="127.0.0.1:8000")
    parser.add_argument("--port", type=tcp_port, default=8080)
    parser.add_argument("--domain")
    parser.add_argument("--cert", type=Path)
    parser.add_argument("--key", type=Path)
    parser.add_argument("--acme-root", type=Path)
    parser.add_argument("--http-port", type=tcp_port, default=80)
    parser.add_argument("--https-port", type=tcp_port, default=443)
    parser.add_argument("--worker-user", default="www-data", help="Linux production Nginx worker account")
    args = parser.parse_args()
    try:
        print(render(args))
    except (ValueError, OSError) as exc:
        parser.error(str(exc))


if __name__ == "__main__":
    main()
