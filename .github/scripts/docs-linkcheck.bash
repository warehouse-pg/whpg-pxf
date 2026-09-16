#!/usr/bin/env bash
#
# docs-linkcheck.bash — static link and anchor integrity sweep for the PXF
# docs book (docs/content) and the top-level user-facing markdown files.
#
# All checks are static file/text checks; no network access.
#
# Checks:
#   1. Book cross-page links:  [x](page.html[#anchor]) in docs/content must
#      resolve to an existing .html.md.erb page; the #anchor (if present)
#      must exist in the target page.
#   2. Book in-page fragments: [x](#fragment) must match an anchor in the
#      same page.
#   3. Subnav targets: every /pxf/<version>/*.html href in the book subnav
#      must have a corresponding content page.
#   4. Orphan pages: every content page must be reachable from the subnav
#      or be linked from another content page.
#   5. Markdown links: relative links in the checked *.md files must point
#      at existing files/directories; a #fragment on a markdown target must
#      match an anchor or heading in the target file.
#
# Anchors are recognized in both id="..." and name="..." forms, plus
# markdown heading slugs; fragment matching is case-insensitive.
#
# Exits non-zero if any check fails, printing one FAIL line per finding.

set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

CONTENT_DIR="docs/content"
SUBNAV_DIR="docs/book/master_middleman/source/subnavs"

# Top-level user-facing markdown surface (the book is covered separately).
MD_FILES=(
  README.md
  CONTRIBUTING.md
  TROUBLESHOOTING.md
  SECURITY.md
  CODE-OF-CONDUCT.md
  docs/README.md
)

FAILURES=0
fail() {
  echo "FAIL: $*"
  FAILURES=$((FAILURES + 1))
}

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

# Print every anchor defined in a file, lowercased, one per line:
# id="..." and name="..." attribute forms, plus GitHub-style slugs of
# markdown headings (lowercase, punctuation stripped, spaces to hyphens).
anchors_in() {
  local file="$1"
  {
    grep -oE '(id|name)="[^"]+"' "$file" 2>/dev/null \
      | sed -E 's/^(id|name)="([^"]+)"$/\2/'
    grep -oE "(id|name)='[^']+'" "$file" 2>/dev/null \
      | sed -E "s/^(id|name)='([^']+)'$/\2/"
    sed -nE 's/^#{1,6} +(.*)$/\1/p' "$file" 2>/dev/null \
      | sed -E 's/\[([^]]*)\]\([^)]*\)/\1/g' \
      | sed -E 's/[`*_]//g' \
      | sed -E 's/[^A-Za-z0-9 _-]//g; s/ /-/g'
  } | tr '[:upper:]' '[:lower:]' | sort -u
}

# Print every markdown link target in a file, one per line: the TEXT inside
# ](...) with any ' "title"' suffix removed.
link_targets_in() {
  grep -oE '\]\([^)[:space:]]+[^)]*\)' "$1" 2>/dev/null \
    | sed -E 's/^\]\(//; s/\)$//; s/[[:space:]]+"[^"]*"$//' \
    || true
}

# True if $2 (lowercased fragment) is among the anchors of file $1.
has_anchor() {
  anchors_in "$1" | grep -qxF "$2"
}

lc() { printf '%s' "$1" | tr '[:upper:]' '[:lower:]'; }

# ---------------------------------------------------------------------------
# Checks 1 + 2: book cross-page links and in-page fragments
# ---------------------------------------------------------------------------

# Content pages are published as <name>.html; sources are <name>.html.md.erb.
erb_pages=()
while IFS= read -r f; do erb_pages+=("$f"); done \
  < <(find "$CONTENT_DIR" -name '*.html.md.erb' | sort)

for page in "${erb_pages[@]}"; do
  page_dir="$(dirname "$page")"
  while IFS= read -r target; do
    [ -n "$target" ] || continue
    case "$target" in
      http://*|https://*|mailto:*|ftp://*) continue ;;   # external: not this sweep
      /*) fail "$page: site-absolute link '$target' (use a relative link)"; continue ;;
      \#*)
        # In-page fragment.
        frag="$(lc "${target#\#}")"
        has_anchor "$page" "$frag" \
          || fail "$page: dead in-page fragment '#$frag'"
        continue
        ;;
    esac
    case "$target" in
      *.html|*.html#*) ;;
      *) continue ;;                                     # non-page asset (image etc.)
    esac
    rel_path="${target%%#*}"
    fragment=""
    [ "$target" != "$rel_path" ] && fragment="${target#*#}"
    target_erb="$page_dir/$rel_path.md.erb"
    if [ ! -f "$target_erb" ]; then
      fail "$page: broken link '$target' (no $target_erb)"
      continue
    fi
    if [ -n "$fragment" ]; then
      has_anchor "$target_erb" "$(lc "$fragment")" \
        || fail "$page: link '$target' — no anchor '#$fragment' in $target_erb"
    fi
  done < <(link_targets_in "$page")
done

# ---------------------------------------------------------------------------
# Check 3: subnav targets
# ---------------------------------------------------------------------------

subnav_pages=""
for subnav in "$SUBNAV_DIR"/*.erb; do
  [ -f "$subnav" ] || continue
  while IFS= read -r href; do
    name="$(basename "$href")"
    name="${name%%#*}"
    subnav_pages="$subnav_pages $name"
    [ -f "$CONTENT_DIR/$name.md.erb" ] || [ -f "$CONTENT_DIR/ref/$name.md.erb" ] \
      || fail "$subnav: href '$href' has no content page"
  done < <(grep -oE 'href="/pxf/[^"]+\.html[^"]*"' "$subnav" | sed -E 's/^href="//; s/"$//')
done

# ---------------------------------------------------------------------------
# Check 4: orphan pages (not in subnav, not linked from any other page)
# ---------------------------------------------------------------------------

# Pages reached from outside the book (e.g. the database upgrade utility's
# own documentation), so no in-book reference exists by design.
ORPHAN_ALLOWLIST="pxf_gpupgrade_pre.html pxf_gpupgrade_post.html"

for page in "${erb_pages[@]}"; do
  html_name="$(basename "$page" .md.erb)"      # e.g. cfg_server.html
  case " $ORPHAN_ALLOWLIST " in *" $html_name "*) continue ;; esac
  case " $subnav_pages " in *" $html_name "*) continue ;; esac
  # Escape regex metacharacters (the '.' in '.html') before interpolating
  # into the pattern — unescaped, a near-matching string in another page
  # (e.g. 'cfg_serverXhtml') would falsely mark a real orphan as linked.
  html_esc=$(printf '%s' "$html_name" | sed 's/[.[\*^$]/\\&/g')
  linked=0
  for other in "${erb_pages[@]}"; do
    [ "$other" = "$page" ] && continue
    if grep -qE "\]\(([^)]*/)?${html_esc}(#[^)]*)?\)" "$other"; then
      linked=1
      break
    fi
  done
  [ "$linked" -eq 1 ] || fail "$page: orphan (not in subnav, not linked from any page)"
done

# ---------------------------------------------------------------------------
# Check 5: markdown relative links and fragments
# ---------------------------------------------------------------------------

for md in "${MD_FILES[@]}"; do
  [ -f "$md" ] || { fail "expected markdown file '$md' is missing"; continue; }
  md_dir="$(dirname "$md")"
  while IFS= read -r target; do
    [ -n "$target" ] || continue
    case "$target" in
      http://*|https://*|mailto:*|ftp://*) continue ;;
      /*) fail "$md: site-absolute link '$target'"; continue ;;
      \#*)
        frag="$(lc "${target#\#}")"
        has_anchor "$md" "$frag" \
          || fail "$md: dead in-page fragment '#$frag'"
        continue
        ;;
    esac
    rel_path="${target%%#*}"
    fragment=""
    [ "$target" != "$rel_path" ] && fragment="${target#*#}"
    resolved="$md_dir/$rel_path"
    if [ ! -e "$resolved" ]; then
      fail "$md: broken link '$target' (no $resolved)"
      continue
    fi
    if [ -n "$fragment" ] && [ -f "$resolved" ]; then
      has_anchor "$resolved" "$(lc "$fragment")" \
        || fail "$md: link '$target' — no anchor '#$fragment' in $resolved"
    fi
  done < <(link_targets_in "$md")
done

# ---------------------------------------------------------------------------

if [ "$FAILURES" -gt 0 ]; then
  echo "docs-linkcheck: $FAILURES failure(s)"
  exit 1
fi
echo "docs-linkcheck: all checks passed"
