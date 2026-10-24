#!/usr/bin/env bash
# Decides whether a job saves a cache entry that it did not restore, and writes <output>=true or false to the step's
# GITHUB_OUTPUT. The oracle and test data actions call it on a miss, before they build or fetch the entry.
#
# The default branch owns the oracle tools, the ffmpeg builds, the smoke vectors and the clips: warm-caches.yml
# fills them there (and CI and the nightly on the default branch save what they miss), and every branch restores
# them from it. The pkg/* snapshot branches are restore-only, so that each one does not keep its own copy of every
# entry in the 10 GB repository cache, with one exception: an entry whose family the default branch holds under
# another key, which means that the branch changed the files the key hashes (oracles.tsv, the ffmpeg build
# script, the smoke rows of vectors.tsv, clips.tsv), is saved on the branch, since the default branch can never
# hold it (an entry of the default branch whose key extends this one, such as the smoke tier's "-partial" twin,
# counts as this one). Other branches and tags (the default branch, pull requests, releases) save what they miss.
#
# Usage: cache-policy.sh <output> <mode> <key> <family>
#   output  the name of the step output to write
#   mode    true or false forces the answer; auto applies the rules above
#   key     the entry's key
#   family  the key prefix shared by every version of the entry, for example oracles-Linux-X64-
# Needs GH_TOKEN with actions: read for the pkg/* rule; when the cache listing fails, the answer is false.
set -uo pipefail
OUTPUT="$1"; MODE="$2"; KEY="$3"; FAMILY="$4"
answer() { echo "$OUTPUT=$1" >> "$GITHUB_OUTPUT"; echo "save $KEY: $1"; exit 0; }
case "$MODE" in
  true|false) answer "$MODE" ;;
esac
case "${GITHUB_REF:-}" in
  refs/heads/pkg/*) ;;
  *) answer true ;;
esac
default="${DEFAULT_BRANCH:-}"
if [ -z "$default" ]; then
  default=$(gh api "repos/$GITHUB_REPOSITORY" --jq .default_branch 2>/dev/null) || {
    echo "::warning::could not look up the default branch (actions: read missing?); $KEY is not saved on this branch"
    answer false
  }
fi
keys=$(gh api -X GET "repos/$GITHUB_REPOSITORY/actions/caches" -f ref="refs/heads/$default" -f key="$FAMILY" \
  -f per_page=100 --jq '.actions_caches[].key' 2>/dev/null) || {
  echo "::warning::could not list the caches of $default (actions: read missing?); $KEY is not saved on this branch"
  answer false
}
if [ -z "$keys" ]; then
  echo "::warning::$default holds no $FAMILY* cache entry: run warm-caches.yml on $default; until then every run of this branch rebuilds $KEY"
  answer false
elif printf '%s\n' "$keys" | tr -d '\r' | awk -v key="$KEY" 'index($0, key) == 1 { found = 1 } END { exit !found }'; then
  answer false
else
  echo "::notice::this branch changed the inputs of $FAMILY*; $KEY is saved on the branch"
  answer true
fi
