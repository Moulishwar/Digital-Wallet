#!/bin/sh
# Run by the nginx image before it starts. With DEMO_MODE=true the app finds /demo.json and shows
# the demo accounts on its cover; otherwise the file is absent and the app is an ordinary wallet.
# DEMO_RESETS_AT, when set, is added to it so the app can say when the demo is wiped.
set -eu
target=/usr/share/nginx/html/demo.json
if [ "${DEMO_MODE:-false}" = "true" ]; then
    if [ -n "${DEMO_RESETS_AT:-}" ]; then
        # demo.json opens with a lone "{" on its first line; the field goes straight after it.
        # Characters that would break the JSON or the sed expression are dropped.
        resets_at=$(printf '%s' "$DEMO_RESETS_AT" | tr -d '\\"/&')
        sed "1s/{/{ \"resetsAt\": \"$resets_at\",/" /usr/share/nginx/demo/demo.json > "$target"
    else
        cp /usr/share/nginx/demo/demo.json "$target"
    fi
    echo "Demo mode: on"
else
    rm -f "$target"
fi
