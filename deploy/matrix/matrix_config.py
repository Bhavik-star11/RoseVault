"""Shared private Synapse configuration for production and disposable local tests."""
import json
import os
from pathlib import Path
import re
import secrets
import textwrap


def render(data, host, public_baseurl, password):
    if not re.fullmatch(r'[a-zA-Z0-9.-]+', host) or '.' not in host:
        raise ValueError('Use a valid homeserver DNS name.')
    if len(password) < 32 or password.startswith('REPLACE_'):
        raise ValueError('Generate a unique database password first.')
    data = Path(data)
    if data.is_symlink():
        raise ValueError('Refusing a symlink as the server data directory.')
    data.mkdir(mode=0o700, exist_ok=True)
    config = data / 'homeserver.yaml'
    contents = f'''
    server_name: {json.dumps(host)}
    public_baseurl: {json.dumps(public_baseurl)}
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
    '''
    descriptor = os.open(config, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, 'w') as output:
        output.write(textwrap.dedent(contents).lstrip())
    return config
