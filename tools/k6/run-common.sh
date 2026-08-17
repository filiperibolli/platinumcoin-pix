# Sourced (not executed) by every tools/k6/run-s*.sh. Resolves REPO_ROOT and provides `k6_run`,
# which runs k6 via a local binary if one is on PATH, otherwise via the grafana/k6 Docker image
# mounting the whole repo at /repo — this dev environment has no local k6 install (confirmed:
# `which k6` finds nothing), and docs/local-dev.md already documents "k6 runs in Docker, no local
# install needed" as this project's convention for the separate PLAN.md step-47 load/k6/ scripts;
# this reuses the same convention here instead of inventing a second one.
#
# All k6 SCRIPT/OUTPUT path arguments must be REPO-RELATIVE (e.g. "tools/k6/s1-conservation.js",
# "docs/load/raw/s1-balance-summary.json"), never absolute — the container only sees /repo, and
# using relative paths throughout means the exact same command line resolves correctly whether k6
# runs natively (cwd=$REPO_ROOT) or in the container (cwd=/repo, 1:1 bind mount). Non-k6 host
# operations in the calling script (ledger-snapshot.sh, jq) are unaffected and keep using
# $REPO_ROOT-absolute paths as before.
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
RAW_DIR_REL="docs/load/raw"
RAW_DIR="$REPO_ROOT/$RAW_DIR_REL"

k6_run() {
  if command -v k6 >/dev/null 2>&1 && [ "${K6_FORCE_DOCKER:-}" != "1" ]; then
    (cd "$REPO_ROOT" && k6 "$@")
  else
    # --user matches the host uid/gid: the image's default (uid 12345) can't write into the
    # bind-mounted docs/load/raw/ (owned by the host user), which surfaced as a silent
    # "permission denied" on --out json without this.
    docker run --rm -i --network=host --user "$(id -u):$(id -g)" -v "$REPO_ROOT:/repo" -w /repo grafana/k6 "$@"
  fi
}
