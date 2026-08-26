#!/usr/bin/env bash

set -euo pipefail

repository="${GITHUB_REPOSITORY:?GITHUB_REPOSITORY is required}"
expected_commit="${EXPECTED_COMMIT:?EXPECTED_COMMIT is required}"
expected_commit="$(printf '%s' "$expected_commit" | tr '[:upper:]' '[:lower:]')"

pulls_json="$(
  gh api \
    --header "Accept: application/vnd.github+json" \
    "repos/$repository/commits/$expected_commit/pulls"
)"
source_pr_json="$(
  jq -c \
    --arg commit "$expected_commit" \
    '[
      .[]
      | select(
          .merged_at != null
          and ((.merge_commit_sha // "") | ascii_downcase) == $commit
          and .base.ref == "master"
        )
    ]
    | if length == 1 then .[0] else empty end' \
    <<< "$pulls_json"
)"

if [ -z "$source_pr_json" ]; then
  echo "Release target must be the merge commit of exactly one merged PR into master" >&2
  exit 1
fi

source_pr_number="$(jq -r '.number' <<< "$source_pr_json")"
source_head_sha="$(jq -r '.head.sha' <<< "$source_pr_json")"
source_head_ref="$(jq -r '.head.ref' <<< "$source_pr_json")"

ci_runs_json="$(
  gh api \
    --method GET \
    "repos/$repository/actions/runs" \
    -f head_sha="$source_head_sha" \
    -f event="pull_request" \
    -f status="success" \
    -F per_page=100
)"

if ! jq -e \
  --arg head_sha "$source_head_sha" \
  --arg head_ref "$source_head_ref" \
  'any(
    .workflow_runs[];
    .path == ".github/workflows/ci.yml"
      and .event == "pull_request"
      and .head_sha == $head_sha
      and .head_branch == $head_ref
      and .conclusion == "success"
  )' \
  >/dev/null \
  <<< "$ci_runs_json"; then
  echo "PR #$source_pr_number has no successful pull-request CI run for $source_head_sha" >&2
  exit 1
fi

echo "Verified release target from PR #$source_pr_number with successful pull-request CI"
