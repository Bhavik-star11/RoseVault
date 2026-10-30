"""Generate private Synapse config once; run locally on your deployment host."""
from pathlib import Path
import json
import re
import secrets

root = Path(__file__).resolve().parent
values = {}
for line in (root / '.env').read_text().splitlines():
    if line.strip() and not line.lstrip().startswith('#'):
        key, value = line.split('=', 1)
        values[key.strip()] = value.strip()
host = values['MATRIX_HOST']
password = values['POSTGRES_PASSWORD']
if not re.fullmatch(r'[a-zA-Z0-9.-]+', host) or '.' not in host:
    raise SystemExit('Use a valid public homeserver DNS name.')
if len(password) < 32 or password.startswith('REPLACE_'):
    raise SystemExit('Generate a unique database password first.')
data = root / 'data'
data.mkdir(mode=0o700, exist_ok=True)
config = data / 'homeserver.yaml'
if config.exists():
    raise SystemExit('Existing homeserver config preserved. Edit it deliberately instead of regenerating keys.')
config.write_text(f'''server_name: {json.dumps(host)}
public_baseurl: {json.dumps('https://' + host + '/')}
pid_file: /data/homeserver.pid
report_stats: false
listeners:
  - port: 8008
    tls: false
    type: http
    x_forwarded: true
    resources:
      - names: [client]
        compress: false
database:
  name: psycopg2
  args:
    user: synapse
    password: {json.dumps(password)}
    database: synapse
    host: postgres
    port: 5432
    cp_min: 5
    cp_max: 10
media_store_path: /data/media_store
max_upload_size: 20M
signing_key_path: /data/homeserver.signing.key
registration_shared_secret: {json.dumps(secrets.token_hex(32))}
macaroon_secret_key: {json.dumps(secrets.token_hex(32))}
form_secret: {json.dumps(secrets.token_hex(32))}
enable_registration: false
enable_registration_without_verification: false
enable_3pid_lookup: false
allow_public_rooms_without_auth: false
allow_public_rooms_over_federation: false
federation_domain_whitelist: []
trusted_key_servers: []
url_preview_enabled: false
''')
config.chmod(0o600)
print('Created private data/homeserver.yaml. Secrets were not printed.')
