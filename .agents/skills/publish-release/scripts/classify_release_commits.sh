#!/usr/bin/env bash

set -euo pipefail

usage() {
  echo "Usage: $0 <base-ref> [target-ref=HEAD] [upstream-ref=upstream/HEAD]" >&2
}

die() {
  echo "Error: $*" >&2
  exit 1
}

if [[ $# -lt 1 || $# -gt 3 ]]; then
  usage
  exit 2
fi

base_ref=$1
target_ref=${2:-HEAD}
upstream_ref=${3:-upstream/HEAD}

git rev-parse --is-inside-work-tree >/dev/null 2>&1 || die "not inside a Git work tree"

base_commit=$(git rev-parse --verify "${base_ref}^{commit}") || die "cannot resolve base ref: ${base_ref}"
target_commit=$(git rev-parse --verify "${target_ref}^{commit}") || die "cannot resolve target ref: ${target_ref}"
upstream_commit=$(git rev-parse --verify "${upstream_ref}^{commit}") || die "cannot resolve upstream ref: ${upstream_ref}"

git merge-base --is-ancestor "$base_commit" "$target_commit" || \
  die "base ref ${base_ref} is not an ancestor of target ${target_ref}"

temp_dir=$(mktemp -d "${TMPDIR:-/tmp}/publish-release.XXXXXX")
trap 'rm -rf "$temp_dir"' EXIT

all_commits="$temp_dir/all-commits"
all_commits_sorted="$temp_dir/all-commits-sorted"
upstream_candidates="$temp_dir/upstream-candidates"
upstream_commits="$temp_dir/upstream-commits"
upstream_merges="$temp_dir/upstream-merges"
other_merges="$temp_dir/other-merges"

: >"$upstream_candidates"
: >"$upstream_merges"
: >"$other_merges"

git rev-list --reverse --topo-order --no-merges "${base_commit}..${target_commit}" >"$all_commits"
sort -u "$all_commits" >"$all_commits_sorted"

short_upstream_ref=${upstream_ref#refs/remotes/}
upstream_remote=${short_upstream_ref%%/*}

while IFS= read -r merge_commit; do
  parents=$(git show -s --format=%P "$merge_commit")
  read -r -a parent_list <<<"$parents"

  if [[ ${#parent_list[@]} -lt 2 ]]; then
    continue
  fi

  first_parent=${parent_list[0]}
  subject=$(git show -s --format=%s "$merge_commit")
  subject_mentions_upstream=0
  case "$subject" in
    *"${upstream_remote}/"*|*"upstream/"*) subject_mentions_upstream=1 ;;
  esac

  first_parent_is_upstream=0
  if git merge-base --is-ancestor "$first_parent" "$upstream_commit"; then
    first_parent_is_upstream=1
  fi

  upstream_parent=""
  for parent in "${parent_list[@]:1}"; do
    if git merge-base --is-ancestor "$parent" "$upstream_commit"; then
      if [[ $first_parent_is_upstream -eq 0 || $subject_mentions_upstream -eq 1 ]]; then
        upstream_parent=$parent
        break
      fi
    fi
  done

  if [[ -n $upstream_parent ]]; then
    echo "$merge_commit" >>"$upstream_merges"
    git rev-list --no-merges "${first_parent}..${upstream_parent}" >>"$upstream_candidates"
  else
    echo "$merge_commit" >>"$other_merges"
  fi
done < <(git rev-list --reverse --topo-order --merges "${base_commit}..${target_commit}")

sort -u "$upstream_candidates" | comm -12 "$all_commits_sorted" - >"$upstream_commits"

print_commit() {
  git show -s --date=short --format='%h  %ad  %an  %s' "$1"
}

print_group() {
  local title=$1
  local membership_file=$2
  local include_matches=$3
  local count=0

  echo
  echo "== ${title} =="
  while IFS= read -r commit; do
    is_member=0
    if grep -qxF "$commit" "$membership_file"; then
      is_member=1
    fi

    if [[ $is_member -eq $include_matches ]]; then
      print_commit "$commit"
      count=$((count + 1))
    fi
  done <"$all_commits"

  if [[ $count -eq 0 ]]; then
    echo "(none)"
  fi
  echo "Count: ${count}"
}

print_merge_group() {
  local title=$1
  local commits_file=$2
  local count=0

  echo
  echo "== ${title} =="
  while IFS= read -r commit; do
    print_commit "$commit"
    count=$((count + 1))
  done <"$commits_file"

  if [[ $count -eq 0 ]]; then
    echo "(none)"
  fi
  echo "Count: ${count}"
}

echo "Release range"
echo "  Base:     ${base_ref} (${base_commit})"
echo "  Target:   ${target_ref} (${target_commit})"
echo "  Upstream: ${upstream_ref} (${upstream_commit})"

print_group "FORK COMMITS" "$upstream_commits" 0
print_group "UPSTREAM COMMITS" "$upstream_commits" 1
print_merge_group "UPSTREAM SYNC MERGES" "$upstream_merges"
print_merge_group "OTHER MERGES" "$other_merges"

if [[ ! -s $upstream_merges ]]; then
  echo
  echo "Note: no topology-preserving upstream merge was detected."
  echo "This is expected when the release contains no upstream sync. If upstream was"
  echo "integrated by squash, rebase, or cherry-pick, classify it manually instead."
fi
