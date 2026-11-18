# Inlined into the installed helper so removal also works without the IDE or plugin files.
GLOBAL_MARKER="$HOME/.prepush-checker/installed"
_pp_dir=$(sed -n '3p' "$GLOBAL_MARKER" 2>/dev/null)
if [ ! -f "$GLOBAL_MARKER" ] || { [ -n "$_pp_dir" ] && [ ! -e "$_pp_dir" ]; }; then
  # Update via a sibling temp file. The running pre-push shell must finish reading the
  # original inode, including any user code after our block. Preserve mode and symlinks.
  strip_plugin_block() (
    file=$1
    kind=$2
    [ -f "$file" ] || return 0
    grep -Eq '^[[:space:]]*# (BEGIN |END )?@MARKER@[[:space:]]*$' "$file" ||
      { [ "$?" -eq 1 ] && return 0; return 1; }
    target=$file
    while [ -L "$target" ]; do
      link=$(readlink "$target") || return 1
      case "$link" in /*) target=$link ;; *) target=$(dirname "$target")/$link ;; esac
    done
    tmp=$(mktemp "${target}.XXXXXX") || return 1
    trap 'rm -f "$tmp"' EXIT
    cp -p "$target" "$tmp" || return 1
    awk -v kind="$kind" -v marker='@MARKER@' -v dirline='@SCRIPT_DIR@' -v call='@CALL@' \
        -v guarded='@GUARDED_CALL@' -v save='@SAVE_STATUS@' -v restore='@RESTORE_STATUS@' '
      function trim(line) { gsub(/^[[:space:]]+|[[:space:]]+$/, "", line); return line }
      function invocation(line) { return line == call || line == call " || exit $?" || line == guarded }
      # Without END, only the exact historical invocation can be claimed safely.
      function flush_unclosed(   i) {
        i = (kind == "hook" && n >= 3 && trim(block[2]) == dirline && invocation(trim(block[3]))) ? 4 : 1
        if (kind == "hook" && n >= 5 && trim(block[2]) == save && trim(block[3]) == dirline &&
            trim(block[4]) == guarded && trim(block[5]) == restore) i=6
        for (; i <= n; i++) print block[i]
        n=0
      }
      {
        line=trim($0)
        if (line == "# BEGIN " marker) { flush_unclosed(); block[++n]=$0; legacy=0; next }
        if (line == "# END " marker) { n=0; legacy=0; next }
        if (n) { block[++n]=$0; next }
        if (line == "# " marker) { legacy=1; next }
        if (legacy == 1 && kind == "hook" && line == dirline) { legacy=2; next }
        if (legacy && kind == "hook" && invocation(line)) { legacy=0; next }
        if (legacy && kind == "exclude" && line == "/.idea/pre-push-checker/") { legacy=0; next }
        legacy=0; print
      }
      END { flush_unclosed() }
    ' "$target" > "$tmp" || return 1
    if cmp -s "$target" "$tmp"; then return 0; fi
    if [ "$kind" = hook ] && [ ! -L "$file" ] && ! grep -Eqv '^[[:space:]]*$|^#!' "$tmp"; then
      rm -f "$file"
    else
      mv -f "$tmp" "$target"
    fi
  )

  clean_hook_dir() {
    [ -d "$1" ] || return 0
    strip_plugin_block "$1/pre-push" hook && rm -f "$1/@HELPER@"
  }

  # Resolve paths from Git for core.hooksPath and linked worktrees.
  _clean=1
  _hooks=$(git rev-parse --git-path hooks) && clean_hook_dir "$_hooks" || _clean=0
  if _common=$(git rev-parse --git-common-dir); then
    clean_hook_dir "$_common/hooks" || _clean=0
    strip_plugin_block "$_common/info/exclude" exclude || _clean=0
  else
    _clean=0
  fi
  rm -rf "$REPO_ROOT/.idea/pre-push-checker" || _clean=0
  rm -f "$GLOBAL_MARKER"
  # Keep entries for other repositories and for cleanup that needs a retry.
  _repos="$HOME/.prepush-checker/repos.txt"
  if [ "$_clean" -eq 1 ] && [ -f "$_repos" ]; then (
    # Serialize concurrent terminal cleanups. A busy registry never holds up a push.
    umask 077
    mkdir "${_repos}.lock" 2>/dev/null || exit 0
    tmp="${_repos}.lock/remaining"
    trap 'rm -f "$tmp"; rmdir "${_repos}.lock"' EXIT
    while IFS= read -r root; do
      [ "$root" -ef "$REPO_ROOT" ] || printf '%s\n' "$root" || exit 1
    done < "$_repos" > "$tmp" || exit 1
    if [ -s "$tmp" ]; then mv -f "$tmp" "$_repos"; else rm -f "$_repos"; fi
  ); fi
  rmdir "$HOME/.prepush-checker" 2>/dev/null || true
  printf '[pre-push] Plugin uninstalled. Skipping compilation check.\n' >&2
  exit 0
fi
