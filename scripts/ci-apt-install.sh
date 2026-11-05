#!/usr/bin/env bash
# apt-get install on a GitHub runner without the manual-page index rebuild, which can take a
# quarter of an hour there and does nothing for a build. Network timeouts and up to three attempts,
# each cut off after 10 minutes: a stuck mirror once hung this step for over half an hour.
set -euo pipefail
sudo rm -f /var/lib/man-db/auto-update
opts=(-o Acquire::Retries=3 -o Acquire::http::Timeout=30 -o Acquire::https::Timeout=30)
attempt() {
  for i in 1 2 3; do
    if timeout 600 "$@"; then return 0; fi
    echo "apt attempt $i failed or timed out; retrying" >&2
    sleep 10
  done
  return 1
}
attempt sudo apt-get "${opts[@]}" update -q
attempt sudo DEBIAN_FRONTEND=noninteractive apt-get "${opts[@]}" install -y -q --no-install-recommends "$@"
