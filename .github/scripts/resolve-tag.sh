#!/usr/bin/env bash
# Parse a release tag into version, scope and human title.
#
#   v0.2.0              → version=0.2.0  scope=all          title="Molecule 0.2.0"
#   molecule-npc-v0.2.0 → version=0.2.0  scope=molecule-npc title="molecule-npc 0.2.0"
#
# Emits GitHub Actions output lines.
set -euo pipefail

tag="${1:?tag required}"

if [[ "${tag}" =~ ^v(.+)$ ]]; then
    version="${BASH_REMATCH[1]}"
    scope="all"
    title="Molecule ${version}"
elif [[ "${tag}" =~ ^(molecule-[a-z]+)-v(.+)$ ]]; then
    scope="${BASH_REMATCH[1]}"
    version="${BASH_REMATCH[2]}"
    title="${scope} ${version}"
else
    echo "Unrecognised tag format: ${tag}" >&2
    echo "Expected 'v<version>' or 'molecule-<module>-v<version>'" >&2
    exit 1
fi

echo "version=${version}"
echo "scope=${scope}"
echo "title=${title}"
