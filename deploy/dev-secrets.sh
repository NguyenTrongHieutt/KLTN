#!/usr/bin/env bash
# Creates the secret files of the local and CI stack (AD-22) in deploy/secrets/, which git ignores.
# Each is a random password with no trailing newline. Existing files are kept, because the
# PostgreSQL volume already holds their values; pass --force to replace them (then also remove the
# volume: docker compose -f deploy/compose.yaml down -v).
#
# Permissions: the directory is 0700, so on the host only its owner can open it. The files are 0444
# because compose mounts each one into containers whose users differ (postgres runs as uid 999, the
# application as uid 10001), and a bind-mounted file keeps the host's mode. See deploy/README.md.
set -euo pipefail

secrets_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/secrets"
force=false
if [[ "${1:-}" == "--force" ]]; then
	force=true
elif [[ $# -gt 0 ]]; then
	echo "usage: $0 [--force]" >&2
	exit 2
fi

umask 077
mkdir -p "$secrets_dir"
chmod 700 "$secrets_dir"

for name in db-superuser-password db-app-password db-migrator-password; do
	file="$secrets_dir/$name"
	if [[ -s "$file" && "$force" == false ]]; then
		echo "kept    $name"
		continue
	fi
	rm -f "$file"
	# 32 random bytes as 64 hexadecimal characters: no quoting issues anywhere they are used.
	value="$(od -An -N32 -tx1 /dev/urandom | tr -d ' \n')"
	if [[ ${#value} -ne 64 ]]; then
		echo "could not read 32 random bytes for $name" >&2
		exit 1
	fi
	printf '%s' "$value" >"$file"
	chmod 444 "$file"
	echo "created $name"
done
