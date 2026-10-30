#!/usr/bin/env python3
"""Prepare an isolated LAN-only development server without changing production data."""
import argparse
import ipaddress
import os
from pathlib import Path
import secrets
from matrix_config import render

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--ip', required=True, help='Your computer Wi-Fi IPv4 address')
parser.add_argument('--port', type=int, default=8008)
args = parser.parse_args()
try:
    address = ipaddress.IPv4Address(args.ip)
except ipaddress.AddressValueError:
    raise SystemExit('Enter an IPv4 address, such as 192.168.1.10.')
allowed = any(address in ipaddress.IPv4Network(block) for block in
              ('10.0.0.0/8', '172.16.0.0/12', '192.168.0.0/16', '127.0.0.0/8'))
if not allowed or not 1024 <= args.port <= 65535:
    raise SystemExit('Use a private/loopback IPv4 address and a port from 1024 to 65535.')
root = Path(__file__).resolve().parent
env = root / '.env.local'
data = root / 'data-local'
if env.exists() or (data / 'homeserver.yaml').exists():
    raise SystemExit('Existing local configuration preserved. See LOCAL_MATRIX.md to restart or change its IP; never regenerate its keys.')
password = secrets.token_hex(32)
uid = getattr(os, 'getuid', lambda: 991)() or 991
gid = getattr(os, 'getgid', lambda: 991)() or 991
values = {'MATRIX_HOST': 'rosevault.test', 'MATRIX_LOCAL_IP': str(address),
          'MATRIX_LOCAL_PORT': str(args.port), 'POSTGRES_PASSWORD': password,
          'SYNAPSE_DATA': './data-local', 'SYNAPSE_UID': str(uid), 'SYNAPSE_GID': str(gid)}
descriptor = os.open(env, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
with os.fdopen(descriptor, 'w') as output:
    output.write(''.join(f'{key}={value}\n' for key, value in values.items()))
config = render(data, 'rosevault.test', f'http://{address}:{args.port}/', password)
if hasattr(os, 'geteuid') and os.geteuid() == 0:
    os.chown(data, uid, gid)
    os.chown(config, uid, gid)
print(f'Local server prepared at http://{address}:{args.port}. Test accounts only; no public signup or TLS.')
print('Production configuration and data were not modified. No secrets were printed.')
