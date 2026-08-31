#!/usr/bin/env bash
set -euo pipefail

script_dir=$(CDPATH='' cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
source_root=$(CDPATH='' cd -- "$script_dir/../.." && pwd)
schema_checker=$script_dir/validate-json-schema.py
run_id=123456789
artifact_id=987654321
repository_id=424242
workflow_id=515151
check_suite_id=616161
tmp_dir=$(mktemp -d)
trap 'rm -rf -- "$tmp_dir"' EXIT
fixture_repo=$tmp_dir/repo
fixtures=$tmp_dir/fixtures
mock_bin=$tmp_dir/bin
payload=$tmp_dir/payload
mkdir -p "$fixture_repo" "$fixtures" "$mock_bin" "$payload" "$tmp_dir/apk-content"
cp -R "$source_root/scripts" "$fixture_repo/"
cp "$source_root/LICENSE" "$fixture_repo/LICENSE"
git -C "$fixture_repo" init -q -b main
git -C "$fixture_repo" add LICENSE scripts
git -C "$fixture_repo" -c user.name=Fixture -c user.email=fixture@example.invalid commit -q -m fixture
source_sha=$(git -C "$fixture_repo" rev-parse HEAD)
policy=$fixture_repo/scripts/apk-provenance-policy.json
validator=$fixture_repo/scripts/verify-debug-apk-provenance.sh
schema=$fixture_repo/scripts/apk-provenance-receipt.schema.json

printf 'fixture manifest\n' >"$tmp_dir/apk-content/AndroidManifest.xml"
(cd -- "$tmp_dir/apk-content" && zip -q "$payload/QUIK-debug.apk" AndroidManifest.xml)
(cd -- "$payload" && sha256sum QUIK-debug.apk >QUIK-debug.apk.sha256)

build_artifact() {
  local output=$1 mode=${2:-valid} build_dir=$tmp_dir/artifact-build
  rm -rf -- "$build_dir"
  mkdir -p "$build_dir"
  cp "$payload/QUIK-debug.apk" "$build_dir/QUIK-debug.apk"
  if [[ $mode == substitute ]]; then
    printf 'substituted signer payload\n' >>"$build_dir/QUIK-debug.apk"
  fi
  (cd -- "$build_dir" && sha256sum QUIK-debug.apk >QUIK-debug.apk.sha256)
  [[ $mode != bad-checksum ]] || printf '%064d  QUIK-debug.apk\n' 0 >"$build_dir/QUIK-debug.apk.sha256"
  [[ $mode != extra-entry ]] || printf 'unexpected\n' >"$build_dir/unexpected.txt"
  if [[ $mode == extra-entry ]]; then
    (cd -- "$build_dir" && zip -q "$output" QUIK-debug.apk QUIK-debug.apk.sha256 unexpected.txt)
  else
    (cd -- "$build_dir" && zip -q "$output" QUIK-debug.apk QUIK-debug.apk.sha256)
  fi
}

build_artifact "$fixtures/artifact.zip"
build_artifact "$fixtures/artifact-substitute.zip" substitute
build_artifact "$fixtures/artifact-bad-checksum.zip" bad-checksum
build_artifact "$fixtures/artifact-extra-entry.zip" extra-entry
git -C "$fixture_repo" archive --format=tar.gz --prefix="only21mil-quik-${source_sha:0:7}/" -o "$fixtures/source.tar.gz" "$source_sha"

license_tamper=$tmp_dir/license-tamper
git -C "$fixture_repo" archive --format=tar --prefix="only21mil-quik-${source_sha:0:7}/" -o "$license_tamper.tar" "$source_sha"
mkdir -p "$license_tamper/only21mil-quik-${source_sha:0:7}"
tar -xf "$license_tamper.tar" -C "$license_tamper"
printf 'not the GPL\n' >"$license_tamper/only21mil-quik-${source_sha:0:7}/LICENSE"
tar -czf "$fixtures/source-license-tamper.tar.gz" -C "$license_tamper" "only21mil-quik-${source_sha:0:7}"

artifact_sha=$(sha256sum "$fixtures/artifact.zip" | awk '{print $1}')
artifact_size=$(stat -c '%s' "$fixtures/artifact.zip")
cat >"$fixtures/repository.json" <<EOF
{"id":$repository_id,"full_name":"only21mil/quik","html_url":"https://github.com/only21mil/quik"}
EOF
cat >"$fixtures/run.json" <<EOF
{
  "id": $run_id, "node_id": "WFR_fixture", "run_number": 17, "run_attempt": 1,
  "workflow_id": $workflow_id, "check_suite_id": $check_suite_id,
  "head_sha": "$source_sha", "status": "completed", "conclusion": "success",
  "event": "push", "head_branch": "main", "name": "Android main",
  "path": ".github/workflows/android-main.yml",
  "html_url": "https://github.com/only21mil/quik/actions/runs/$run_id",
  "repository": {"id": $repository_id, "full_name": "only21mil/quik"},
  "head_repository": {"id": $repository_id, "full_name": "only21mil/quik"}
}
EOF
cat >"$fixtures/artifact.json" <<EOF
{
  "id": $artifact_id, "node_id": "ART_fixture",
  "name": "quik-android-debug-$source_sha", "size_in_bytes": $artifact_size,
  "url": "https://api.github.com/repos/only21mil/quik/actions/artifacts/$artifact_id",
  "archive_download_url": "https://api.github.com/repos/only21mil/quik/actions/artifacts/$artifact_id/zip",
  "expired": false, "digest": "sha256:$artifact_sha",
  "workflow_run": {"id": $run_id, "repository_id": $repository_id, "head_repository_id": $repository_id, "head_branch": "main", "head_sha": "$source_sha"}
}
EOF

cat >"$mock_bin/gh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >>"$MOCK_GH_LOG"
case ${1:-} in
  version)
    printf 'gh version fixture\n'
    ;;
  auth)
    [[ ${2:-} == token && " $* " == *' --hostname github.com '* ]] || exit 91
    printf 'fixture-token\n'
    ;;
  api)
    [[ " $* " == *' --hostname github.com '* ]] || exit 92
    [[ " $* " == *' --method GET '* ]] || exit 93
    [[ " $* " == *' Accept: application/vnd.github+json '* ]] || exit 94
    [[ " $* " == *' X-GitHub-Api-Version: 2022-11-28 '* ]] || exit 95
    endpoint=${!#}
    case $endpoint in
      repos/only21mil/quik) cat "$MOCK_FIXTURES/repository.json" ;;
      repos/only21mil/quik/actions/runs/*)
        if [[ ${MOCK_BAD_RUN:-0} == 1 ]]; then jq '.path = ".github/workflows/substitute.yml"' "$MOCK_FIXTURES/run.json"
        else cat "$MOCK_FIXTURES/run.json"; fi
        ;;
      repos/only21mil/quik/actions/artifacts/*/zip)
        case ${MOCK_ARTIFACT_ARCHIVE:-valid} in
          valid) cat "$MOCK_FIXTURES/artifact.zip" ;;
          substitute) cat "$MOCK_FIXTURES/artifact-substitute.zip" ;;
          bad-checksum) cat "$MOCK_FIXTURES/artifact-bad-checksum.zip" ;;
          extra-entry) cat "$MOCK_FIXTURES/artifact-extra-entry.zip" ;;
        esac
        ;;
      repos/only21mil/quik/actions/artifacts/*)
        if [[ ${MOCK_BAD_ARTIFACT_METADATA:-0} == 1 ]]; then jq '.workflow_run.repository_id = 999999' "$MOCK_FIXTURES/artifact.json"
        else cat "$MOCK_FIXTURES/artifact.json"; fi
        ;;
      repos/only21mil/quik/tarball/*)
        if [[ ${MOCK_SOURCE_ARCHIVE:-valid} == license-tamper ]]; then cat "$MOCK_FIXTURES/source-license-tamper.tar.gz"
        else cat "$MOCK_FIXTURES/source.tar.gz"; fi
        ;;
      *) exit 96 ;;
    esac
    ;;
  *) exit 97 ;;
esac
EOF

cat >"$mock_bin/aapt" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
if [[ ${1:-} == version ]]; then printf 'Android Asset Packaging Tool, vfixture\n'; exit 0; fi
emit_attribute() {
  printf '%*sA: android:%s(0x01010003)="%s" (Raw: "%s")\n' "$1" '' "$2" "$3" "$3"
}
emit_filter() {
  local component=$1 index=$2 value
  printf '        E: intent-filter\n'
  while IFS= read -r value; do
    [[ -n $value ]] || continue
    if [[ ${MOCK_RECOMBINE_FILTERS:-0} == 1 && $component == activity:dev.octoshrimpy.quik.feature.compose.ComposeActivity ]]; then
      [[ $index != 2 || $value != android.intent.action.SEND ]] || value=android.intent.action.SEND_MULTIPLE
      [[ $index != 3 || $value != android.intent.action.SEND_MULTIPLE ]] || value=android.intent.action.SEND
    fi
    printf '          E: action\n'; emit_attribute 12 name "$value"
  done < <(jq -r --arg c "$component" --argjson i "$((index - 1))" '.allowed_exported_intent_filters[] | select(.component == $c) | .filters[$i].actions[]' "$MOCK_POLICY")
  for field in categories data_schemes data_mime_types; do
    while IFS= read -r value; do
      [[ -n $value ]] || continue
      case $field in
        categories) printf '          E: category\n'; emit_attribute 12 name "$value" ;;
        data_schemes) printf '          E: data\n'; emit_attribute 12 scheme "$value" ;;
        data_mime_types) printf '          E: data\n'; emit_attribute 12 mimeType "$value" ;;
      esac
    done < <(jq -r --arg c "$component" --argjson i "$((index - 1))" --arg f "$field" '.allowed_exported_intent_filters[] | select(.component == $c) | .filters[$i][$f][]' "$MOCK_POLICY")
  done
}
emit_component() {
  local component=$1 kind=${1%%:*} name=${1#*:} prefix=$1= permission count index
  printf '    E: %s\n' "$kind"; emit_attribute 6 name "$name"
  printf '      A: android:exported(0x01010010)=(type 0x12)0xffffffff\n'
  permission=$(jq -r --arg prefix "$prefix" '.allowed_exported_component_permissions[] | select(startswith($prefix)) | .[($prefix | length):]' "$MOCK_POLICY")
  [[ -z $permission ]] || emit_attribute 6 permission "$permission"
  count=$(jq -r --arg c "$component" '.allowed_exported_intent_filters[] | select(.component == $c) | .filters | length' "$MOCK_POLICY")
  for ((index = 1; index <= count; index++)); do emit_filter "$component" "$index"; done
}
case ${2:-} in
  badging)
    printf "%s\n" "package: name='io.github.only21mil.quik.reactions.debug' versionCode='2238001' versionName='4.3.6-reactions.1-debug'" "sdkVersion:'23'" "targetSdkVersion:'33'" application-debuggable
    ;;
  permissions)
    jq -r '.allowed_permissions[]' "$MOCK_POLICY" | while IFS= read -r permission; do
      [[ ${MOCK_MISSING_PERMISSION:-0} != 1 || $permission != android.permission.SEND_SMS ]] || continue
      printf "uses-permission: name='%s'\n" "$permission"
    done
    if [[ ${MOCK_MISSING_DECLARED_PERMISSION:-0} != 1 ]]; then jq -r '.declared_permissions[] | "permission: \(.)"' "$MOCK_POLICY"; fi
    ;;
  xmltree)
    printf 'E: manifest\n  E: application\n'
    jq -r '.allowed_exported_components[]' "$MOCK_POLICY" | while IFS= read -r component; do emit_component "$component"; done
    ;;
  *) exit 2 ;;
esac
EOF

cat >"$mock_bin/apksigner" <<'EOF'
#!/usr/bin/env bash
if [[ ${1:-} == version ]]; then printf '0.fixture\n'; exit 0; fi
digest=AA
[[ ${MOCK_SIGNER_SUBSTITUTION:-0} != 1 ]] || digest=BB
printf 'Signer #1 certificate SHA-256 digest: %s' "$digest"
for _ in {2..32}; do printf ':%s' "$digest"; done
printf '\n'
EOF
chmod +x "$mock_bin/gh" "$mock_bin/aapt" "$mock_bin/apksigner" "$validator"

run_validator() {
  : >"$tmp_dir/gh.log"
  PATH="$mock_bin:$PATH" MOCK_FIXTURES="$fixtures" MOCK_GH_LOG="$tmp_dir/gh.log" MOCK_POLICY="$policy" "$validator" \
    --repo-root "$fixture_repo" --source-sha "$source_sha" --ci-run-id "$run_id" --artifact-id "$artifact_id" --receipt "$tmp_dir/receipt.json"
}
expect_failure() {
  local label=$1
  shift
  if "$@" 2>/dev/null; then
    printf '%s fixture unexpectedly passed\n' "$label" >&2
    exit 1
  fi
}
run_recombined_filters() { MOCK_RECOMBINE_FILTERS=1 run_validator; }
run_missing_permission() { MOCK_MISSING_PERMISSION=1 run_validator; }
run_missing_declaration() { MOCK_MISSING_DECLARED_PERMISSION=1 run_validator; }
run_bad_run() { MOCK_BAD_RUN=1 run_validator; }
run_bad_artifact_metadata() { MOCK_BAD_ARTIFACT_METADATA=1 run_validator; }
run_coordinated_substitution() { MOCK_ARTIFACT_ARCHIVE=substitute MOCK_SIGNER_SUBSTITUTION=1 run_validator; }
run_bad_checksum() { MOCK_ARTIFACT_ARCHIVE=bad-checksum run_validator; }
run_extra_entry() { MOCK_ARTIFACT_ARCHIVE=extra-entry run_validator; }
run_license_tamper() { MOCK_SOURCE_ARCHIVE=license-tamper run_validator; }

run_validator
python3 "$schema_checker" "$schema" "$tmp_dir/receipt.json"
jq -e --arg sha "$source_sha" --arg run "$run_id" --arg artifact "$artifact_id" '
  .result == "pass" and .acquisition.authenticated == true and .acquisition.caller_supplied_evidence == false and
  .source.commit == $sha and .source.archive.exact_git_file_set == true and .source.license.spdx == "GPL-3.0-or-later" and
  .github_run.id == $run and .github_artifact.id == $artifact and .delivery.result == "complete" and
  (.apk.permissions | length) == 18 and
  (.apk.permissions | index("io.github.only21mil.quik.reactions.debug.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")) != null and
  (.apk.declared_permissions == ["io.github.only21mil.quik.reactions.debug.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"]) and
  (.apk.exported_intent_filters[] | select(.component == "activity:dev.octoshrimpy.quik.feature.compose.ComposeActivity") | .filters | length) == 3 and
  (.tools | keys | length) == 9
' "$tmp_dir/receipt.json" >/dev/null
[[ $(grep -c '^api --hostname github.com --method GET ' "$tmp_dir/gh.log") -eq 5 ]]
[[ $(grep -c '^auth token --hostname github.com$' "$tmp_dir/gh.log") -eq 1 ]]

jq '.unexpected = true' "$tmp_dir/receipt.json" >"$tmp_dir/receipt-extra.json"
expect_failure schema-extra-property python3 "$schema_checker" "$schema" "$tmp_dir/receipt-extra.json"
jq '.source.license.spdx = "GPL-3.0-only"' "$tmp_dir/receipt.json" >"$tmp_dir/receipt-old-source-license.json"
expect_failure schema-old-source-license python3 "$schema_checker" "$schema" "$tmp_dir/receipt-old-source-license.json"
jq '.delivery.license = "GPL-3.0-only"' "$tmp_dir/receipt.json" >"$tmp_dir/receipt-old-delivery-license.json"
expect_failure schema-old-delivery-license python3 "$schema_checker" "$schema" "$tmp_dir/receipt-old-delivery-license.json"
expect_failure coordinated-caller-metadata-archive-apk-signer-substitution \
  "$validator" --repo-root "$fixture_repo" --source-sha "$source_sha" --ci-run-id "$run_id" --artifact-id "$artifact_id" \
  --github-run-json "$fixtures/run.json" --github-artifact-json "$fixtures/artifact.json" --artifact-archive "$fixtures/artifact-substitute.zip"
expect_failure substituted-run run_bad_run
expect_failure substituted-artifact-metadata run_bad_artifact_metadata
expect_failure coordinated-metadata-archive-apk-signer-substitution run_coordinated_substitution
expect_failure embedded-checksum-tamper run_bad_checksum
expect_failure archive-entry-substitution run_extra_entry
expect_failure corresponding-source-license-tamper run_license_tamper
expect_failure permission-drift run_missing_permission
expect_failure declared-permission-drift run_missing_declaration
expect_failure intent-filter-recombination run_recombined_filters

if [[ -n ${QUIK_EXACT_APK:-} && -n ${AAPT_BIN:-} && -n ${APKSIGNER_BIN:-} ]]; then
  [[ -f $QUIK_EXACT_APK && -x $AAPT_BIN && -x $APKSIGNER_BIN ]]
  cp "$QUIK_EXACT_APK" "$payload/QUIK-debug.apk"
  build_artifact "$fixtures/artifact.zip"
  artifact_sha=$(sha256sum "$fixtures/artifact.zip" | awk '{print $1}')
  artifact_size=$(stat -c '%s' "$fixtures/artifact.zip")
  jq --arg digest "sha256:$artifact_sha" --argjson size "$artifact_size" \
    '.digest = $digest | .size_in_bytes = $size' "$fixtures/artifact.json" >"$fixtures/artifact-exact.json"
  mv "$fixtures/artifact-exact.json" "$fixtures/artifact.json"
  exact_bin=$tmp_dir/exact-bin
  mkdir -p "$exact_bin"
  ln -s "$mock_bin/gh" "$exact_bin/gh"
  ln -s "$AAPT_BIN" "$exact_bin/aapt"
  ln -s "$APKSIGNER_BIN" "$exact_bin/apksigner"
  : >"$tmp_dir/gh.log"
  PATH="$exact_bin:$PATH" MOCK_FIXTURES="$fixtures" MOCK_GH_LOG="$tmp_dir/gh.log" "$validator" \
    --repo-root "$fixture_repo" --source-sha "$source_sha" --ci-run-id "$run_id" --artifact-id "$artifact_id" --receipt "$tmp_dir/exact-receipt.json"
  python3 "$schema_checker" "$schema" "$tmp_dir/exact-receipt.json"
  jq -e '(.apk.permissions | length) == 18 and (.apk.exported_intent_filters | length) == 12' "$tmp_dir/exact-receipt.json" >/dev/null
  printf 'exact APK policy: pass\n'
fi

printf 'fixture self-tests: pass\n'
