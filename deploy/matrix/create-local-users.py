#!/usr/bin/env python3
"""Create two disposable non-admin accounts inside the local Synapse container."""
import argparse
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import subprocess

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--prefix', default='demo')
parser.add_argument('--output', type=Path)
args = parser.parse_args()
if not re.fullmatch(r'[a-z0-9-]{1,32}', args.prefix):
    raise SystemExit('Use a short lowercase test-account prefix.')
root = Path(__file__).resolve().parent
env = dict(line.split('=', 1) for line in (root / '.env.local').read_text().splitlines()
           if line.strip() and not line.startswith('#'))
if env.get('MATRIX_HOST') != 'rosevault.test' or env.get('SYNAPSE_DATA') != './data-local':
    raise SystemExit('This helper is only for the isolated local development server.')
output = args.output or root / 'data-local' / 'test-accounts.json'
records = [{'name': f'{args.prefix}-{name}', 'password': secrets.token_urlsafe(24)}
           for name in ('alice', 'bob')]
fixture = {'origin': f"http://{env['MATRIX_LOCAL_IP']}:{env['MATRIX_LOCAL_PORT']}",
           'users': [{'id': f"@{record['name']}:rosevault.test", 'password': record['password']}
                     for record in records], 'created': False}
# Save first so a partial registration failure never loses the generated test credentials.
descriptor = os.open(output, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
with os.fdopen(descriptor, 'w') as file:
    json.dump(fixture, file, indent=2)
docker = shutil.which('docker')
if not docker:
    raise SystemExit('Docker CLI was not found. The private fixture was preserved; accounts were not created.')
script = '''import hashlib, hmac, json, urllib.request, yaml
config = yaml.safe_load(open('/data/homeserver.yaml'))
assert config['server_name'] == 'rosevault.test'
secret = config['registration_shared_secret']
def request(data=None):
    req = urllib.request.Request('http://127.0.0.1:8008/_synapse/admin/v1/register',
        data=json.dumps(data).encode() if data is not None else None,
        headers={'Content-Type': 'application/json'})
    with urllib.request.urlopen(req, timeout=30) as result:
        return json.load(result)
for record in RECORDS:
    nonce = request()['nonce']
    mac = hmac.new(secret.encode(), digestmod=hashlib.sha1)
    for value in (nonce, record['name'], record['password']):
        mac.update(value.encode()); mac.update(b'\\x00')
    mac.update(b'notadmin')
    request({'nonce': nonce, 'username': record['name'], 'password': record['password'],
             'admin': False, 'inhibit_login': True, 'mac': mac.hexdigest()})
print('Local non-admin accounts created.')
'''.replace('RECORDS', repr(records))
command = [docker, 'compose', '--project-name', 'rosevault-local', '--project-directory', str(root),
           '--env-file', str(root / '.env.local'), '-f', str(root / 'compose.yaml'),
           '-f', str(root / 'compose.local.yaml'), 'exec', '-T', 'synapse', 'python', '-']
result = subprocess.run(command, input=script, text=True, capture_output=True)
if result.returncode:
    raise SystemExit('Local registration did not finish. Private credentials were preserved; inspect the local server, and use a fresh prefix/output for a retry. No accounts were reset.')
fixture['created'] = True
output.write_text(json.dumps(fixture, indent=2))
output.chmod(0o600)
print(f'Two non-admin test accounts created. Open {output} privately to view credentials; never commit or share that file.')
