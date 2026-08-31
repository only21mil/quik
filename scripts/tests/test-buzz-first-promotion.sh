#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
controller="$repo_root/scripts/prepare-buzz-first-promotion.sh"
fixture_root="$(mktemp -d)"
trap 'rm -rf "$fixture_root"' EXIT

authoritative_main='4706cb38f9d7d38c5b2dd971286fcded51e180a3'
upstream_master='555b8822c654b8ee85bb9d3f961eb30f232b1079'
candidate_ref='refs/heads/sats/test-buzz-promotion'
message_file="$fixture_root/message.txt"
printf 'test: prepare exact Buzz-first promotion topology\n' >"$message_file"

git clone --local --no-hardlinks "$repo_root" "$fixture_root/repo" >/dev/null
fixture_repo="$fixture_root/repo"

bootstrap_object="$({
	git -C "$fixture_repo" hash-object -t commit -w --stdin <<'EOF'
tree a44b19a7f8acfd1908462ab1b8227f69d5b39673
parent 555b8822c654b8ee85bb9d3f961eb30f232b1079
author Victor Vogel <263261067+only21mil@users.noreply.github.com> 1788115102 -0500
committer Victor Vogel <263261067+only21mil@users.noreply.github.com> 1788115102 -0500

ci: target pull request checks at main
EOF
})"
[[ "$bootstrap_object" == "$authoritative_main" ]]

git -C "$fixture_repo" branch -f main "$authoritative_main"
git -C "$fixture_repo" branch -f master "$upstream_master"

refs_before="$(git -C "$fixture_repo" for-each-ref \
	--format='%(refname)%09%(objectname)' refs | sort)"
main_before="$(git -C "$fixture_repo" rev-parse refs/heads/main)"
master_before="$(git -C "$fixture_repo" rev-parse refs/heads/master)"

output="$({
	cd "$fixture_repo"
	QUIK_PROMOTION_AUTHOR_NAME='QUIK topology test' \
		QUIK_PROMOTION_AUTHOR_EMAIL='quik-topology-test@invalid.example' \
		QUIK_PROMOTION_TIMESTAMP='2026-08-30T12:00:00-05:00' \
		"$controller" create \
		--settled HEAD \
		--candidate-ref "$candidate_ref" \
		--message-file "$message_file"
})"

grep -Fxq 'result=PASS' <<<"$output"
candidate="$(sed -n 's/^candidate_commit=//p' <<<"$output")"
[[ "$candidate" =~ ^[0-9a-f]{40}$ ]]
candidate_parent="$(git -C "$fixture_repo" cat-file commit "$candidate" |
	sed -n 's/^parent //p')"
candidate_tree="$(git -C "$fixture_repo" cat-file commit "$candidate" |
	sed -n 's/^tree //p')"
settled_tree="$(git -C "$fixture_repo" cat-file commit HEAD |
	sed -n 's/^tree //p')"
[[ "$candidate_parent" == "$authoritative_main" ]]
[[ "$candidate_tree" == "$settled_tree" ]]
git -C "$fixture_repo" diff-tree --quiet HEAD "$candidate"
git -C "$fixture_repo" merge-base --is-ancestor "$authoritative_main" "$candidate"
[[ "$(git -C "$fixture_repo" rev-parse refs/heads/main)" == "$main_before" ]]
[[ "$(git -C "$fixture_repo" rev-parse refs/heads/master)" == "$master_before" ]]

refs_after="$(git -C "$fixture_repo" for-each-ref \
	--format='%(refname)%09%(objectname)' refs | sort)"
expected_refs_after="$(printf '%s\n%s\t%s\n' "$refs_before" "$candidate_ref" "$candidate" | sort)"
[[ "$refs_after" == "$expected_refs_after" ]]

replacement_candidate_ref='refs/heads/sats/test-buzz-promotion-replacement'
settled_commit="$(git -C "$fixture_repo" rev-parse HEAD)"
settled_tree_without_replacement="$(
	git -C "$fixture_repo" --no-replace-objects cat-file commit "$settled_commit" |
		sed -n 's/^tree //p'
)"
replacement_tree="$(git -C "$fixture_repo" mktree </dev/null)"
replacement_commit="$({
	GIT_AUTHOR_NAME='QUIK topology replacement test' \
		GIT_AUTHOR_EMAIL='quik-topology-test@invalid.example' \
		GIT_AUTHOR_DATE='2026-08-30T12:00:00-05:00' \
		GIT_COMMITTER_NAME='QUIK topology replacement test' \
		GIT_COMMITTER_EMAIL='quik-topology-test@invalid.example' \
		GIT_COMMITTER_DATE='2026-08-30T12:00:00-05:00' \
		git -C "$fixture_repo" commit-tree "$replacement_tree" -p "$authoritative_main" <<'EOF'
test: replacement object must not affect promotion topology
EOF
})"
git -C "$fixture_repo" replace "$settled_commit" "$replacement_commit"

replacement_visible_tree="$(git -C "$fixture_repo" cat-file commit "$settled_commit" |
	sed -n 's/^tree //p')"
[[ "$replacement_visible_tree" == "$replacement_tree" ]]
[[ "$replacement_visible_tree" != "$settled_tree_without_replacement" ]]

replacement_output="$({
	cd "$fixture_repo"
	QUIK_PROMOTION_AUTHOR_NAME='QUIK topology test' \
		QUIK_PROMOTION_AUTHOR_EMAIL='quik-topology-test@invalid.example' \
		QUIK_PROMOTION_TIMESTAMP='2026-08-30T12:00:00-05:00' \
		"$controller" create \
		--settled "$settled_commit" \
		--candidate-ref "$replacement_candidate_ref" \
		--message-file "$message_file"
})"

grep -Fxq 'result=PASS' <<<"$replacement_output"
replacement_candidate="$(sed -n 's/^candidate_commit=//p' <<<"$replacement_output")"
reported_tree="$(sed -n 's/^tree=//p' <<<"$replacement_output")"
replacement_candidate_tree="$(
	git -C "$fixture_repo" --no-replace-objects cat-file commit "$replacement_candidate" |
		sed -n 's/^tree //p'
)"
[[ "$reported_tree" == "$settled_tree_without_replacement" ]]
[[ "$replacement_candidate_tree" == "$settled_tree_without_replacement" ]]
[[ "$replacement_candidate_tree" != "$replacement_tree" ]]
git -C "$fixture_repo" --no-replace-objects diff-tree --quiet \
	"$settled_commit" "$replacement_candidate"

if rg -n 'git[[:space:]]+(fetch|push|pull|ls-remote)|\bgh\b|\bcurl\b|\bwget\b|\bssh\b' \
	"$controller"; then
	echo 'Controller contains a network-capable command.' >&2
	exit 1
fi

if {
	cd "$fixture_repo"
	QUIK_PROMOTION_AUTHOR_NAME='QUIK topology test' \
		QUIK_PROMOTION_AUTHOR_EMAIL='quik-topology-test@invalid.example' \
		QUIK_PROMOTION_TIMESTAMP='2026-08-30T12:00:00-05:00' \
		"$controller" create \
		--settled HEAD \
		--candidate-ref refs/heads/main \
		--message-file "$message_file"
} >"$fixture_root/rejected-ref.log" 2>&1; then
	echo 'Controller accepted main as a candidate ref.' >&2
	exit 1
fi

git -C "$fixture_repo" branch -f master "$authoritative_main"
if {
	cd "$fixture_repo"
	QUIK_PROMOTION_AUTHOR_NAME='QUIK topology test' \
		QUIK_PROMOTION_AUTHOR_EMAIL='quik-topology-test@invalid.example' \
		QUIK_PROMOTION_TIMESTAMP='2026-08-30T12:00:00-05:00' \
		"$controller" create \
		--settled HEAD \
		--candidate-ref refs/heads/sats/reject-wrong-master \
		--message-file "$message_file"
} >"$fixture_root/rejected-master.log" 2>&1; then
	echo 'Controller accepted a moved master ref.' >&2
	exit 1
fi
grep -Fq 'local master must equal untouched upstream' "$fixture_root/rejected-master.log"

echo 'Buzz-first promotion topology checks passed.'
