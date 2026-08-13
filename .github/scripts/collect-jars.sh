#!/usr/bin/env bash
# Gather the plugin jars that should be attached to a release.
#
# Usage: collect-jars.sh <scope>
#
# Modules carrying a `.scaffold` marker are registered in the build but not yet
# implemented. They still compile — that keeps the build graph honest — but they
# must never be attached to a release, because an empty jar published as a
# plugin misrepresents what the project can do.
set -euo pipefail

scope="${1:-all}"
mkdir -p dist

collected=0
skipped=()

for module in molecule-*/; do
    module="${module%/}"

    if [[ "${scope}" != "all" && "${scope}" != "${module}" ]]; then
        continue
    fi

    if [[ -f "${module}/.scaffold" ]]; then
        skipped+=("${module}")
        continue
    fi

    # Sources jars are useful on the release page but are not the plugin itself;
    # take the plain artifact only.
    while IFS= read -r jar; do
        cp "${jar}" dist/
        collected=$((collected + 1))
    done < <(find "${module}/build/libs" -name '*.jar' \
        ! -name '*-sources.jar' 2>/dev/null || true)
done

if [[ ${#skipped[@]} -gt 0 ]]; then
    echo "Not yet implemented, excluded from this release: ${skipped[*]}"
fi

if [[ "${collected}" -eq 0 ]]; then
    echo "No releasable jars found for scope '${scope}'." >&2
    echo "Every matching module is still scaffolding — remove its .scaffold" >&2
    echo "marker once it has a working plugin main class." >&2
    exit 1
fi

echo "Collected ${collected} jar(s):"
ls -1 dist/
