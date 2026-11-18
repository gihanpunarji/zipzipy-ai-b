#!/usr/bin/env python3
"""Create a private deployment env file without overwriting existing credentials."""
import argparse
import os
from pathlib import Path
import re
import secrets

parser = argparse.ArgumentParser()
parser.add_argument('--host', required=True, help='Public DNS hostname, without https://')
parser.add_argument('--email', required=True, help='TLS certificate contact email')
args = parser.parse_args()
if not re.fullmatch(r'[a-zA-Z0-9](?:[a-zA-Z0-9.-]*[a-zA-Z0-9])?', args.host) or '.' not in args.host:
    parser.error('Use a DNS hostname such as zipzipy-demo.duckdns.org')
if not re.fullmatch(r'[a-zA-Z0-9_.+@-]+', args.email) or args.email.count('@') != 1:
    parser.error('Provide a valid contact email')
path = Path(__file__).resolve().parent.parent / '.env.production'
try:
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
except FileExistsError:
    parser.error('.env.production already exists. Edit it manually; credentials were preserved.')
with os.fdopen(fd, 'w') as file:
    file.write(f'''PUBLIC_HOST={args.host}
ACME_EMAIL={args.email}
DB_PASSWORD={secrets.token_hex(32)}
OLLAMA_MODEL=gemma4:e2b
OLLAMA_EMBEDDING_MODEL=nomic-embed-text:latest
OLLAMA_CONTEXT_SIZE=8192
OLLAMA_MAX_OUTPUT_TOKENS=800
GOOGLE_LOGIN_ENABLED=false
GOOGLE_CLIENT_ID=
GOOGLE_CLIENT_SECRET=
MAIL_ENABLED=false
MAIL_HOST=
MAIL_PORT=587
MAIL_USERNAME=
MAIL_PASSWORD=
MAIL_FROM=
''')
print('Created private .env.production with a generated database password. No secrets printed.')
