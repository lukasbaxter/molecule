#!/usr/bin/env bash
# Generate release notes for a tag, grouped by change type and split per module.
#
# Usage: release-notes.sh <scope> <tag>
#   scope = "all", or a module name such as "molecule-npc"
#
# Commit subjects are read as Conventional Commits (`fix(npc): ...`), which is
# what makes per-module fix notes possible without maintaining them by hand.
# Anything unparseable still appears, under Other — notes must never silently
# drop a change.
set -euo pipefail

scope="${1:-all}"
tag="${2:?tag required}"

# Previous tag in the same series, so a module tag diffs against that module's
# last release rather than against the ecosystem-wide one.
if [[ "${scope}" == "all" ]]; then
    pattern='v*'
else
    pattern="${scope}-v*"
fi
previous="$(git tag --list "${pattern}" --sort=-version:refname \
    | grep -v "^${tag}$" | head -n1 || true)"

if [[ -n "${previous}" ]]; then
    range="${previous}..${tag}"
    echo "Changes since [\`${previous}\`](../../releases/tag/${previous})."
else
    range="${tag}"
    echo "First release."
fi
echo

modules() {
    if [[ "${scope}" == "all" ]]; then
        find . -maxdepth 1 -type d -name 'molecule-*' -exec basename {} \; | sort
    else
        echo "${scope}"
    fi
}

# Conventional Commit type -> release-notes heading. Order matters: fixes are
# what people scan a plugin release for, so they come first.
section_for() {
    case "$1" in
        fix)             echo "Fixed" ;;
        feat)            echo "Added" ;;
        perf)            echo "Performance" ;;
        refactor|change) echo "Changed" ;;
        docs)            echo "Documentation" ;;
        *)               echo "Other" ;;
    esac
}

emitted_any=0

for module in $(modules); do
    [[ -d "${module}" ]] || continue

    # Skip modules with no history in this range — an unreleased module should
    # not appear in notes with an empty section.
    # Newline-delimited rather than an array: macOS ships bash 3.2, which has
    # no mapfile, and this script must run locally as well as on the runner.
    commits="$(git log --no-merges --format='%s' "${range}" -- "${module}" || true)"
    [[ -n "${commits}" ]] || continue

    body=""
    for section in Fixed Added Changed Performance Documentation Other; do
        lines=""
        while IFS= read -r subject; do
            [[ -n "${subject}" ]] || continue
            type="${subject%%[(:]*}"
            [[ "$(section_for "${type}")" == "${section}" ]] || continue
            # Strip the `type(scope):` prefix; keep the human half.
            text="${subject#*: }"
            lines+="- ${text}"$'\n'
        done <<< "${commits}"
        [[ -n "${lines}" ]] || continue
        body+="#### ${section}"$'\n'"${lines}"$'\n'
    done

    [[ -n "${body}" ]] || continue
    echo "### ${module}"
    echo
    echo "${body}"
    emitted_any=1
done

if [[ "${emitted_any}" -eq 0 ]]; then
    echo "No module changes recorded in this range."
    echo
fi

echo "---"
echo
echo "Built against Folia 1.21.x, Java 21. See \`docs/SPEC.md\` for architecture."
