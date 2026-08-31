#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
fixture_dir="$(mktemp -d)"
negative_log="$(mktemp)"
trap 'rm -rf "$fixture_dir"; rm -f "$negative_log"' EXIT

gradle() {
  "$repo_root/gradlew" --no-daemon --offline --console=plain -p "$repo_root" "$@"
}

expect_rejection() {
  local case_name=$1 expected=$2
  shift 2

  if gradle "$@" >"$negative_log" 2>&1; then
    echo "$case_name was accepted." >&2
    exit 1
  fi

  if ! grep -Fq 'Release signing configuration rejected:' "$negative_log" ||
      ! grep -Fq "$expected" "$negative_log"; then
    echo "$case_name did not return the expected error: $expected" >&2
    sed -n '1,180p' "$negative_log" >&2
    exit 1
  fi
}

unset QUIK_RELEASE_KEYSTORE_PATH
unset QUIK_RELEASE_KEY_ALIAS
unset QUIK_RELEASE_STORE_PASSWORD
unset QUIK_RELEASE_KEY_PASSWORD
unset QUIK_RELEASE_SIGNING_APPROVED_BY
unset QUIK_RELEASE_SIGNING_APPROVAL_REF

artifact_tasks=(
  assembleRelease bundleRelease packageRelease packageReleaseBundle
  packageReleaseUniversalApk signReleaseBundle
  assembleFdroid bundleFdroid packageFdroid packageFdroidBundle
  packageFdroidUniversalApk signFdroidBundle
)
for task in "${artifact_tasks[@]}"; do
  expect_rejection "$task without release configuration" \
    'QUIK_RELEASE_KEYSTORE_PATH is required' \
    ":presentation:$task" --dry-run
done
grep -Fq 'QUIK_RELEASE_SIGNING_APPROVAL_REF is required' "$negative_log" || {
  echo 'Artifact task validation did not require an approval reference.' >&2
  sed -n '1,180p' "$negative_log" >&2
  exit 1
}

fixture_path="$fixture_dir/disposable-test-input"
printf 'not a keystore; validation must not read this file\n' >"$fixture_path"
chmod 600 "$fixture_path"

export QUIK_RELEASE_KEYSTORE_PATH="$fixture_path"
export QUIK_RELEASE_KEY_ALIAS='non-secret-test-alias'
export QUIK_RELEASE_STORE_PASSWORD='non-secret-test-value'
export QUIK_RELEASE_KEY_PASSWORD='non-secret-test-value'
export QUIK_RELEASE_SIGNING_APPROVED_BY='Victor'
export QUIK_RELEASE_SIGNING_APPROVAL_REF='test-only-disposable-fixture'

gradle :presentation:validateReleaseSigningConfiguration

export QUIK_RELEASE_SIGNING_APPROVED_BY=' Victor'
expect_rejection 'approval with leading whitespace' \
  'QUIK_RELEASE_SIGNING_APPROVED_BY must be Victor or Rachel' \
  :presentation:validateReleaseSigningConfiguration

export QUIK_RELEASE_SIGNING_APPROVED_BY='Rachel '
expect_rejection 'approval with trailing whitespace' \
  'QUIK_RELEASE_SIGNING_APPROVED_BY must be Victor or Rachel' \
  :presentation:validateReleaseSigningConfiguration
export QUIK_RELEASE_SIGNING_APPROVED_BY='Victor'

chmod 640 "$fixture_path"
expect_rejection 'group-readable keystore metadata' \
  'QUIK_RELEASE_KEYSTORE_PATH must not grant group or other access' \
  :presentation:validateReleaseSigningConfiguration

chmod 604 "$fixture_path"
expect_rejection 'other-readable keystore metadata' \
  'QUIK_RELEASE_KEYSTORE_PATH must not grant group or other access' \
  :presentation:validateReleaseSigningConfiguration
chmod 600 "$fixture_path"

relative_path="$(basename "$fixture_path")"
export QUIK_RELEASE_KEYSTORE_PATH="$relative_path"
expect_rejection 'relative keystore path' \
  'QUIK_RELEASE_KEYSTORE_PATH must be absolute' \
  :presentation:validateReleaseSigningConfiguration

symlink_path="$fixture_dir/disposable-test-symlink"
ln -s "$fixture_path" "$symlink_path"
export QUIK_RELEASE_KEYSTORE_PATH="$symlink_path"
expect_rejection 'symbolic-link keystore path' \
  'QUIK_RELEASE_KEYSTORE_PATH must not be a symbolic link' \
  :presentation:validateReleaseSigningConfiguration

owner_mismatch_path=/etc/passwd
if [[ ! -f $owner_mismatch_path ]]; then
  echo 'Owner-mismatch fixture /etc/passwd is unavailable.' >&2
  exit 1
fi
if [[ $(stat -c '%u' "$owner_mismatch_path") == "$(id -u)" ]]; then
  owner_mismatch_path="$fixture_dir/foreign-owned-test-input"
  printf 'owner metadata fixture; validation must not read this file\n' >"$owner_mismatch_path"
  chmod 600 "$owner_mismatch_path"
  chown 1 "$owner_mismatch_path"
fi
export QUIK_RELEASE_KEYSTORE_PATH="$owner_mismatch_path"
expect_rejection 'foreign-owned keystore metadata' \
  'QUIK_RELEASE_KEYSTORE_PATH must be owned by the effective signing user' \
  :presentation:validateReleaseSigningConfiguration

export QUIK_RELEASE_KEYSTORE_PATH="$repo_root/README.md"
expect_rejection 'repository-local keystore path' \
  'QUIK_RELEASE_KEYSTORE_PATH must resolve outside the repository' \
  :presentation:validateReleaseSigningConfiguration

if grep -En "storeFile[[:space:]]+file\\(['\"]\\./|\\.gradle/\\.gradlerc|System\\.getenv\\(['\"](keystore_password|key_alias|key_password)" \
    "$repo_root/presentation/build.gradle"; then
  echo 'The legacy repository-local or CI signing configuration remains.' >&2
  exit 1
fi

echo 'release configuration guard checks passed'
