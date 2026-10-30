"""Generate production Synapse config once; never overwrite existing keys."""
from pathlib import Path
from matrix_config import render

root = Path(__file__).resolve().parent
values = {}
for line in (root / '.env').read_text().splitlines():
    if line.strip() and not line.lstrip().startswith('#'):
        key, value = line.split('=', 1)
        values[key.strip()] = value.strip()
try:
    render(root / 'data', values['MATRIX_HOST'], 'https://' + values['MATRIX_HOST'] + '/', values['POSTGRES_PASSWORD'])
except FileExistsError:
    raise SystemExit('Existing config preserved. Do not regenerate server keys.')
print('Created private data/homeserver.yaml. Secrets were not printed. Match its ownership to SYNAPSE_UID/GID before starting.')
