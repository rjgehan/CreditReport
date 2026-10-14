#!/usr/bin/env sh
# Syntax-checks every front-end module as an ES module.
#
# Plain `node --check file.js` is not enough: outside a "type": "module" package
# Node can accept a broken .js file without parsing it as a module. Copying each
# file to .mjs forces a real module parse.
set -eu
root="$(cd "$(dirname "$0")/.." && pwd)"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
status=0
for f in $(find "$root/src/main/resources/static" -name '*.js'); do
  copy="$tmp/$(echo "$f" | sed 's|[/\\:]|_|g').mjs"
  cp "$f" "$copy"
  if ! node --check "$copy" 2>"$tmp/err"; then
    echo "Syntax error in ${f#$root/}:"
    cat "$tmp/err"
    status=1
  fi
done
[ "$status" -eq 0 ] && echo "All front-end modules parse."
exit "$status"
