# Emit one tab-separated record per error. An unexplained line vetoes suppression.
# POSIX awk (including macOS) supports RSTART/RLENGTH without GNU capture arrays.
extract_error_records() {
  LC_ALL=C awk '
    function record(path, kind) { print path "\t" kind }
    function unknown() { record("-", "unknown") }
    {
      line=$0
      sub(/\r$/, "", line)
      gsub(/\033\[[0-9;]*m/, "", line)
      sub(/^[[:space:]]+/, "", line)
      sub(/^\[ERROR\][[:space:]]*/, "", line)
      sub(/^@MAVEN_GOAL@:[[:space:]]+/, "", line)
      sub(/[[:space:]]+$/, "", line)
      # Javac prints an excerpt and a caret immediately after a located diagnostic.
      if (pending) {
        pending=0
        if (line ~ /^\^~*$/) next
        unknown()
      }
      wasDiagnostic=located
      located=0
      warning=(line ~ /^w:[[:space:]]+/)
      kotlin=sub(/^[ew]:[[:space:]]+/, "", line)
      if (match(line, /[.](java|kt|kts|scala|groovy):/)) {
        end=RSTART+RLENGTH-2
        path=substr(line, 1, end)
        msg=substr(line, end+2)
        if (sub(/^\[[0-9]+,[0-9]+\][[:space:]]*/, "", msg) ||
            (kotlin && sub(/^[0-9]+:[0-9]+:?[[:space:]]+/, "", msg)) ||
            (msg ~ /^[0-9]+(:[0-9]+)?:[[:space:]]*(error|warning):/)) {
          if (msg ~ /^[0-9]+(:[0-9]+)?:[[:space:]]*warning:/) warning=1
          sub(/^[0-9]+(:[0-9]+)?:[[:space:]]*(error|warning):[[:space:]]*/, "", msg)
          if (!length(msg) || tolower(msg) ~ /@INFRA@/ || path ~ /[[:cntrl:]]/) unknown()
          else if (warning) warnings++
          else { record(path,"error"); errors++ }
          located=1
          next
        }
      }
      if (line ~ /^[0-9]+ errors?$/) {
        if (line+0 > errors) unknown()
        next
      }
      if (line ~ /^[0-9]+ warnings?$/) {
        if (line+0 > warnings) unknown()
        next
      }
      if (!length(line) || line ~ /@WRAPPER@/) next
      if (wasDiagnostic && line ~ /^(symbol|location):[[:space:]]/) { located=1; next }
      if (wasDiagnostic && !kotlin && $0 !~ /^[[:space:]]*\[ERROR\]/) { pending=1; next }
      unknown()
    }
    END { if (pending) unknown() }
  ' "$1" > "$2" || { : > "$2"; return 1; }
}

all_compiler_records_ignored() {
  [ "$LAST_BUILD_TOOL" != custom ] && [ -s "$1" ] && [ -n "$FALLBACK_IGNORE_RULES" ] || return 1
  printf '%s\n' "$FALLBACK_IGNORE_RULES" | LC_ALL=C awk -F '\t' -v host_os="$(uname -s)" -v root="$SNAPSHOT_WORKTREE" '
    function uriPath(s,    i,h,n,p) {
      if (s !~ /^file:\/\/\//) return s
      s=substr(s,8)
      for (i=1;i<=length(s);i++) {
        if (substr(s,i,1)!="%") { p=p substr(s,i,1); continue }
        h=toupper(substr(s,i+1,2))
        if (h !~ /^[0-9A-F][0-9A-F]$/) return ""
        n=16*(index("0123456789ABCDEF",substr(h,1,1))-1)+index("0123456789ABCDEF",substr(h,2,1))-1
        if (n<32 || n==47 || n==92 || n==127) return ""
        p=p sprintf("%c",n); i+=2
      }
      return p
    }
    function canonical(p) {
      if (host_os=="Darwin" && p ~ /^\/private\/(tmp|var)\//) return substr(p,9)
      return p
    }
    FILENAME==ARGV[1] {
      if (index($0,"ignoredFile=")==1) ignored[substr($0,13)]=1
      next
    }
    {
      if (NF!=2 || $2!="error") { blocked=1; exit }
      path=canonical(uriPath($1)); prefix=canonical(root) "/"
      if (index(path,prefix)==1) path=substr(path,length(prefix)+1)
      sub(/^\.\//,"",path)
      if (path ~ /(^|\/)\.\.(\/|$)|^(\/|[A-Za-z]:)|(^|\/)(settings|build)[.]gradle[.]kts$/ || !(path in ignored)) {
        blocked=1; exit
      }
      seen=1
    }
    END { exit !(seen && !blocked) }
  ' - "$1"
}
