#!/bin/sh
# Secure-by-default startup for self-hosted FiGuard.
#
# Generates a unique credential pepper and webhook-encryption key on first boot and
# persists them to the data volume, so every self-hosted install gets its own stable
# secrets with zero configuration. An explicitly-provided env var always wins.
#
# Persistence matters: these must live on the same durable volume as the database.
# If the DB survives a restart but the pepper regenerates, every stored token hash
# breaks. The secrets file is mounted at FIGUARD_SECRETS_FILE (default /data/secrets.env).

set -e

SECRETS_FILE="${FIGUARD_SECRETS_FILE:-/data/secrets.env}"
mkdir -p "$(dirname "$SECRETS_FILE")"
touch "$SECRETS_FILE"
chmod 600 "$SECRETS_FILE" 2>/dev/null || true

# 48 chars of base64 from the kernel CSPRNG — no openssl dependency.
gen_secret() {
    head -c 36 /dev/urandom | base64 | tr -d '\n='
}

# Resolve a secret with precedence: explicit env var > persisted file > generate+persist.
ensure_secret() {
    var_name="$1"
    eval "current=\${$var_name:-}"

    if [ -n "$current" ]; then
        echo "[figuard] $var_name provided via environment — using it."
        return
    fi

    persisted=$(grep "^${var_name}=" "$SECRETS_FILE" 2>/dev/null | head -1 | cut -d= -f2-)
    if [ -n "$persisted" ]; then
        export "$var_name=$persisted"
        echo "[figuard] $var_name loaded from $SECRETS_FILE."
        return
    fi

    value=$(gen_secret)
    echo "${var_name}=${value}" >> "$SECRETS_FILE"
    export "$var_name=$value"
    echo "[figuard] $var_name not set — generated a unique secret and persisted it to $SECRETS_FILE."
}

ensure_secret FIGUARD_TOKEN_PEPPER
ensure_secret WEBHOOK_SECRET_KEY

exec java -jar app.jar "$@"
