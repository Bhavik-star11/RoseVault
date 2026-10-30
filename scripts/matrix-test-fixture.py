#!/usr/bin/env python3
"""Create disposable users ONLY on a localhost Synapse test server. Never print secrets."""
import argparse
import hashlib
import hmac
import json
from pathlib import Path
import re
import secrets
import ssl
import urllib.parse
import urllib.request

parser = argparse.ArgumentParser()
parser.add_argument('--config', type=Path, required=True)
parser.add_argument('--ca', type=Path, required=True)
parser.add_argument('--origin', default='https://localhost:8448')
parser.add_argument('--android-origin', default='https://10.0.2.2:8448')
parser.add_argument('--output', type=Path, required=True)
args = parser.parse_args()
origin = urllib.parse.urlsplit(args.origin)
if origin.scheme != 'https' or origin.hostname not in ('localhost', '127.0.0.1') or origin.username or origin.password or origin.query or origin.fragment:
    raise SystemExit('This helper is restricted to an HTTPS localhost test server.')
if args.output.exists():
    raise SystemExit('Existing fixture preserved; choose a fresh output path.')
match = re.search(r'^registration_shared_secret:\s*(.+)$', args.config.read_text(), re.M)
if not match:
    raise SystemExit('The test config has no shared registration secret.')
value = match.group(1).strip()
secret = json.loads(value) if value.startswith('"') else value
ctx = ssl.create_default_context(cafile=str(args.ca))
def request(path, data=None):
    req = urllib.request.Request(args.origin.rstrip('/') + path,
        data=json.dumps(data).encode() if data is not None else None,
        headers={'Content-Type': 'application/json'})
    with urllib.request.urlopen(req, context=ctx, timeout=30) as response:
        return json.load(response)
users = []
prefix = 'rose_test_' + secrets.token_hex(4)
for role in ('alice', 'bob'):
    username = prefix + '_' + role
    password = secrets.token_hex(24)
    nonce = request('/_synapse/admin/v1/register')['nonce']
    payload = b'\0'.join(x.encode() for x in (nonce, username, password, 'notadmin'))
    mac = hmac.new(secret.encode(), payload, hashlib.sha1).hexdigest()
    result = request('/_synapse/admin/v1/register', {'nonce': nonce, 'username': username,
        'password': password, 'admin': False, 'mac': mac})
    users.append({'id': result['user_id'], 'password': password, 'accessToken': result['access_token']})
args.output.parent.mkdir(parents=True, exist_ok=True)
with args.output.open('x') as out:
    out.write(json.dumps({'origin': args.android_origin, 'certificate': args.ca.read_text(), 'users': users}))
args.output.chmod(0o600)
print('Two disposable localhost users prepared. Keep the fixture private and remove it after testing.')
