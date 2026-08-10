#!/bin/sh
set -eu

repository_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
failures=0

require_file() {
  relative_path=$1
  if [ ! -f "$repository_root/$relative_path" ]; then
    printf 'missing required file: %s\n' "$relative_path" >&2
    failures=$((failures + 1))
  fi
}

require_text() {
  relative_path=$1
  expected=$2
  if [ -f "$repository_root/$relative_path" ] &&
     ! grep -Fq "$expected" "$repository_root/$relative_path"; then
    printf '%s must contain: %s\n' "$relative_path" "$expected" >&2
    failures=$((failures + 1))
  fi
}

for required_file in \
  AGENTS.md \
  CLAUDE.md \
  GEMINI.md \
  .github/copilot-instructions.md \
  CONTRIBUTING.md \
  SECURITY.md \
  .github/CODEOWNERS \
  .editorconfig \
  .gitattributes \
  .gitignore
do
  require_file "$required_file"
done

for adapter in CLAUDE.md GEMINI.md .github/copilot-instructions.md
do
  require_text "$adapter" "AGENTS.md"
  if [ -f "$repository_root/$adapter" ] &&
     [ "$(wc -l < "$repository_root/$adapter" | tr -d ' ')" -gt 20 ]; then
    printf '%s must remain a thin adapter of at most 20 lines\n' "$adapter" >&2
    failures=$((failures + 1))
  fi
done

require_text AGENTS.md "## Architecture boundaries"
require_text AGENTS.md "## Standard workflow"
require_text AGENTS.md "## Verification contract"
require_text AGENTS.md "## Prohibited changes"

if [ "$failures" -ne 0 ]; then
  exit 1
fi

printf 'Repository policy check passed.\n'
