#!/usr/bin/env bash
# Writes .env from .env.example with freshly generated secrets: database passwords, the
# service-to-service credential and the RSA keypair auth-service signs tokens with. Any KEY=VALUE
# arguments are set too:
#
#   deploy/make-env.sh                                  # for running it on this machine
#   deploy/make-env.sh DEMO_MODE=true ADMIN_EMAILS=auditor@demo.example.com
#
# Refuses to replace an existing .env: new keys would sign everyone out, and new database
# passwords would no longer match the databases.
set -euo pipefail
cd "$(dirname "$0")/.."

if [ -e .env ]; then
    echo ".env already exists; not replacing it." >&2
    exit 1
fi

put() {
    if grep -q "^$1=" .env; then
        sed -i "s|^$1=.*|$1=$2|" .env
    else
        echo "$1=$2" >> .env
    fi
}

cp .env.example .env
chmod 600 .env

keys=$(mktemp -d)
trap 'rm -rf "$keys"' EXIT
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -outform DER -out "$keys/priv.der" 2> /dev/null
openssl pkcs8 -topk8 -nocrypt -inform DER -in "$keys/priv.der" -outform DER -out "$keys/priv_pkcs8.der"
openssl rsa -in "$keys/priv.der" -inform DER -pubout -outform DER -out "$keys/pub.der" 2> /dev/null
put JWT_PRIVATE_KEY "$(base64 -w0 "$keys/priv_pkcs8.der")"
put JWT_PUBLIC_KEY "$(base64 -w0 "$keys/pub.der")"
for key in WALLET_DB_PASSWORD AUTH_DB_PASSWORD TRANSFER_DB_PASSWORD SERVICE_CREDENTIAL; do
    put "$key" "$(openssl rand -hex 24)"
done

for setting in "$@"; do
    put "${setting%%=*}" "${setting#*=}"
done

echo "Wrote .env"
