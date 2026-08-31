#!/usr/bin/env bash
set -euo pipefail

# Promotion topology must be derived from stored commit objects, never local
# replacement refs that can substitute different commit contents.
export GIT_NO_REPLACE_OBJECTS=1

readonly authoritative_main='4706cb38f9d7d38c5b2dd971286fcded51e180a3'
readonly upstream_master='555b8822c654b8ee85bb9d3f961eb30f232b1079'

usage() {
	cat >&2 <<'EOF'
Usage:
  prepare-buzz-first-promotion.sh create \
    --settled COMMIT \
    --candidate-ref refs/heads/sats/NAME \
    --message-file ABSOLUTE_PATH

The create command writes one local candidate ref. It never moves main or
master and has no remote operation.
EOF
}

die() {
	printf 'ERROR: %s\n' "$*" >&2
	exit 1
}

git_read() {
	case "${1:-}" in
	cat-file | diff-tree | for-each-ref | merge-base | rev-parse | status)
		git "$@"
		;;
	*)
		die "internal Git read command is not allowed: ${1:-missing}"
		;;
	esac
}

git_write_candidate_ref() {
	[[ "${1:-}" == update-ref ]] || die 'only update-ref may write local Git state'
	git "$@"
}

require_commit() {
	local object="$1"
	git_read cat-file -e "${object}^{commit}" 2>/dev/null ||
		die "required commit is missing: $object"
}

ref_value() {
	git_read rev-parse --verify "$1" 2>/dev/null ||
		die "required local ref is missing: $1"
}

commit_parents() {
	git_read cat-file commit "$1" | sed -n 's/^parent //p' | paste -sd ' ' -
}

commit_tree() {
	git_read cat-file commit "$1" | sed -n 's/^tree //p'
}

mode="${1:-}"
shift || true
[[ "$mode" == create ]] || {
	usage
	exit 2
}

settled=''
candidate_ref=''
message_file=''
while (($#)); do
	case "$1" in
	--settled)
		(($# >= 2)) || die '--settled requires a value'
		settled="$2"
		shift 2
		;;
	--candidate-ref)
		(($# >= 2)) || die '--candidate-ref requires a value'
		candidate_ref="$2"
		shift 2
		;;
	--message-file)
		(($# >= 2)) || die '--message-file requires a value'
		message_file="$2"
		shift 2
		;;
	*)
		die "unknown argument: $1"
		;;
	esac
done

[[ -n "$settled" ]] || die '--settled is required'
[[ "$candidate_ref" =~ ^refs/heads/sats/[A-Za-z0-9._/-]+$ ]] ||
	die '--candidate-ref must name a refs/heads/sats/ ref'
[[ "$candidate_ref" != *'..'* && "$candidate_ref" != */.lock && "$candidate_ref" != */ ]] ||
	die '--candidate-ref is not a valid candidate ref'
[[ "$message_file" == /* ]] || die '--message-file must be an absolute path'
[[ -f "$message_file" && ! -L "$message_file" && -s "$message_file" ]] ||
	die '--message-file must be a non-empty regular file, not a symlink'

: "${QUIK_PROMOTION_AUTHOR_NAME:?QUIK_PROMOTION_AUTHOR_NAME is required}"
: "${QUIK_PROMOTION_AUTHOR_EMAIL:?QUIK_PROMOTION_AUTHOR_EMAIL is required}"
: "${QUIK_PROMOTION_TIMESTAMP:?QUIK_PROMOTION_TIMESTAMP is required}"

repo_root="$(git_read rev-parse --show-toplevel)"
cd "$repo_root"

require_commit "$authoritative_main"
require_commit "$upstream_master"
require_commit "$settled"
settled="$(git_read rev-parse --verify "${settled}^{commit}")"

main_before="$(ref_value refs/heads/main)"
master_before="$(ref_value refs/heads/master)"
[[ "$main_before" == "$authoritative_main" ]] ||
	die "local main must equal authoritative bootstrap $authoritative_main"
[[ "$master_before" == "$upstream_master" ]] ||
	die "local master must equal untouched upstream $upstream_master"

base_parents="$(commit_parents "$authoritative_main")"
[[ "$base_parents" == "$upstream_master" ]] ||
	die 'the authoritative bootstrap no longer has the upstream commit as its sole parent'

if git_read rev-parse --verify "$candidate_ref" >/dev/null 2>&1; then
	die "candidate ref already exists: $candidate_ref"
fi

refs_before="$(git_read for-each-ref --format='%(refname)%09%(objectname)' refs | sort)"
status_before="$(git_read status --porcelain=v2 --untracked-files=all)"
settled_tree="$(commit_tree "$settled")"

candidate="$({
	GIT_AUTHOR_NAME="$QUIK_PROMOTION_AUTHOR_NAME" \
		GIT_AUTHOR_EMAIL="$QUIK_PROMOTION_AUTHOR_EMAIL" \
		GIT_AUTHOR_DATE="$QUIK_PROMOTION_TIMESTAMP" \
		GIT_COMMITTER_NAME="$QUIK_PROMOTION_AUTHOR_NAME" \
		GIT_COMMITTER_EMAIL="$QUIK_PROMOTION_AUTHOR_EMAIL" \
		GIT_COMMITTER_DATE="$QUIK_PROMOTION_TIMESTAMP" \
		git commit-tree "$settled_tree" -p "$authoritative_main" <"$message_file"
})"

[[ "$(commit_parents "$candidate")" == "$authoritative_main" ]] ||
	die 'candidate does not have the authoritative commit as its sole parent'
[[ "$(commit_tree "$candidate")" == "$settled_tree" ]] ||
	die 'candidate tree differs from the settled tree'
git_read diff-tree --quiet "$settled" "$candidate" ||
	die 'candidate tree diff is not empty against the settled commit'
git_read merge-base --is-ancestor "$authoritative_main" "$candidate" ||
	die 'candidate is not a fast-forward from authoritative main'

git_write_candidate_ref update-ref "$candidate_ref" "$candidate" ''

[[ "$(ref_value refs/heads/main)" == "$main_before" ]] || die 'local main moved'
[[ "$(ref_value refs/heads/master)" == "$master_before" ]] || die 'local master moved'
[[ "$(git_read status --porcelain=v2 --untracked-files=all)" == "$status_before" ]] ||
	die 'the worktree changed while preparing the candidate'

refs_after="$(git_read for-each-ref --format='%(refname)%09%(objectname)' refs | sort)"
expected_refs_after="$(printf '%s\n%s\t%s\n' "$refs_before" "$candidate_ref" "$candidate" | sed '/^$/d' | sort)"
[[ "$refs_after" == "$expected_refs_after" ]] ||
	die 'a ref other than the requested local candidate ref changed'

printf 'result=PASS\n'
printf 'candidate_ref=%s\n' "$candidate_ref"
printf 'candidate_commit=%s\n' "$candidate"
printf 'parent=%s\n' "$authoritative_main"
printf 'tree=%s\n' "$settled_tree"
printf 'settled_commit=%s\n' "$settled"
printf 'main_unchanged=%s\n' "$main_before"
printf 'master_unchanged=%s\n' "$master_before"
