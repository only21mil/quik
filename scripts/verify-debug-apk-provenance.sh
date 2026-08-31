#!/usr/bin/env bash
set -euo pipefail

die() {
  printf 'apk provenance: %s\n' "$*" >&2
  exit 1
}

usage() {
  cat >&2 <<'EOF'
Usage: verify-debug-apk-provenance.sh \
  --repo-root DIR --source-sha SHA --ci-run-id ID --artifact-id ID \
  [--receipt FILE]

Acquires the repository, run, artifact, artifact ZIP, and corresponding-source
archive through the authenticated GitHub CLI session for github.com. It never
accepts caller-supplied API responses or archives, builds, signs, installs, or
uploads an APK.
EOF
}

repo_root=
source_sha=
ci_run_id=
artifact_id=
receipt=/dev/stdout

while (($#)); do
  case $1 in
    --repo-root | --source-sha | --ci-run-id | --artifact-id | --receipt)
      (($# >= 2)) || die "$1 needs a value"
      case $1 in
        --repo-root) repo_root=$2 ;;
        --source-sha) source_sha=$2 ;;
        --ci-run-id) ci_run_id=$2 ;;
        --artifact-id) artifact_id=$2 ;;
        --receipt) receipt=$2 ;;
      esac
      shift 2
      ;;
    -h | --help)
      usage
      exit 0
      ;;
    *) die "unknown argument: $1" ;;
  esac
done

[[ -n $repo_root && -n $source_sha && -n $ci_run_id && -n $artifact_id ]] || {
  usage
  die 'missing required argument'
}
[[ $source_sha =~ ^[0-9a-f]{40}$ ]] || die 'source SHA must be 40 lowercase hexadecimal characters'
[[ $ci_run_id =~ ^[1-9][0-9]*$ ]] || die 'CI run ID must be a positive decimal integer'
[[ $artifact_id =~ ^[1-9][0-9]*$ ]] || die 'artifact ID must be a positive decimal integer'
[[ -d $repo_root ]] || die 'repository root is not a directory'

for command_name in git gh jq unzip tar aapt apksigner sha256sum stat awk sort basename mktemp sed grep diff install cat wc cmp; do
  command -v "$command_name" >/dev/null 2>&1 || die "required command not found: $command_name"
done

actual_root=$(git -C "$repo_root" rev-parse --show-toplevel 2>/dev/null) || die 'repository root is not a Git worktree'
canonical_root=$(cd -- "$repo_root" && pwd -P)
[[ $actual_root == "$canonical_root" ]] || die '--repo-root must name the Git worktree root'
actual_sha=$(git -C "$canonical_root" rev-parse --verify HEAD)
[[ $actual_sha == "$source_sha" ]] || die 'checkout HEAD does not match requested source SHA'
[[ -z $(git -C "$canonical_root" status --porcelain=v1 --untracked-files=all) ]] || die 'Git worktree is not clean'

validator=$canonical_root/scripts/verify-debug-apk-provenance.sh
policy=$canonical_root/scripts/apk-provenance-policy.json
receipt_schema=$canonical_root/scripts/apk-provenance-receipt.schema.json
for source_file in "$validator" "$policy" "$receipt_schema"; do
  [[ -f $source_file && -r $source_file && ! -L $source_file ]] || die "source-owned verifier file is unavailable: $source_file"
done
invoked_validator=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)/$(basename -- "${BASH_SOURCE[0]}")
[[ $invoked_validator == "$validator" ]] || die 'validator must run from the exact source checkout'

verify_source_blob() {
  local relative_path=$1
  local expected_blob actual_blob
  expected_blob=$(git -C "$canonical_root" rev-parse "$source_sha:$relative_path") || die "source does not track $relative_path"
  actual_blob=$(git -C "$canonical_root" hash-object -- "$canonical_root/$relative_path") || die "could not hash $relative_path"
  [[ $actual_blob == "$expected_blob" ]] || die "$relative_path differs from the source commit"
}
verify_source_blob scripts/verify-debug-apk-provenance.sh
verify_source_blob scripts/apk-provenance-policy.json
verify_source_blob scripts/apk-provenance-receipt.schema.json

jq -e . "$policy" >/dev/null || die 'policy is not valid JSON'
jq -e . "$receipt_schema" >/dev/null || die 'receipt schema is not valid JSON'
jq -e '
  .policy_schema_version == 3 and
  .validator_version == 3 and
  .receipt_schema_id == "urn:only21mil:quik:apk-provenance-receipt:3" and
  .receipt_schema_version == 3 and
  .repository == "only21mil/quik" and
  .source_ref == "refs/heads/main" and
  (.workflow | type == "object") and
  (.artifact_name_template | type == "string" and contains("{source_sha}")) and
  (.archive_entries == ["QUIK-debug.apk", "QUIK-debug.apk.sha256"]) and
  (.source_delivery.license_path == "LICENSE") and
  (.source_delivery.license_spdx == "GPL-3.0-or-later") and
  (.package | type == "object") and
  (.signer.mode == "ephemeral-github-actions-debug") and
  (.signer.expected_certificate_sha256 == null) and
  (.signer.identity_bound == false) and
  (.signer.limitation | type == "string" and length > 0) and
  ([.allowed_sensitive_permissions[]] - [.allowed_permissions[]] | length == 0) and
  all(
    .allowed_permissions,
    .declared_permissions,
    .allowed_sensitive_permissions,
    .allowed_exported_components,
    .allowed_exported_component_permissions;
    type == "array" and length > 0 and all(.[]; type == "string" and length > 0) and length == (unique | length)
  ) and
  (.allowed_exported_intent_filters | type == "array") and
  ((.allowed_exported_intent_filters | length) == (.allowed_exported_components | length)) and
  ((.allowed_exported_intent_filters | map(.component) | sort) == (.allowed_exported_components | sort)) and
  all(.allowed_exported_intent_filters[];
    (.component | type == "string" and length > 0) and
    (.filters | type == "array") and
    all(.filters[];
      all(.actions, .categories, .data_schemes, .data_mime_types;
        type == "array" and length == (unique | length) and all(.[]; type == "string" and length > 0)
      )
    )
  )
' "$policy" >/dev/null || die 'provenance policy structure is invalid'

schema_id=$(jq -er '."$id"' "$receipt_schema") || die 'receipt schema ID is missing'
schema_version=$(jq -er '.properties.schema_version.const' "$receipt_schema") || die 'receipt schema version is missing'
[[ $schema_id == "$(jq -er '.receipt_schema_id' "$policy")" && $schema_version == "$(jq -er '.receipt_schema_version' "$policy")" ]] || die 'policy and receipt schema identity differ'

policy_repository=$(jq -er '.repository' "$policy")
policy_source_ref=$(jq -er '.source_ref' "$policy")
policy_workflow_name=$(jq -er '.workflow.name' "$policy")
policy_workflow_path=$(jq -er '.workflow.path' "$policy")
policy_event=$(jq -er '.workflow.event' "$policy")
policy_branch=$(jq -er '.workflow.head_branch' "$policy")
expected_artifact_name=$(jq -nr --arg template "$(jq -er '.artifact_name_template' "$policy")" --arg sha "$source_sha" '$template | gsub("\\{source_sha\\}"; $sha)')
template_value() {
  jq -nr --arg template "$1" --arg sha "$source_sha" '$template | gsub("\\{source_sha\\}"; $sha)'
}
source_commit_url=$(template_value "$(jq -er '.source_delivery.commit_url_template' "$policy")")
source_archive_api=$(template_value "$(jq -er '.source_delivery.archive_api_template' "$policy")")
source_archive_url=$(template_value "$(jq -er '.source_delivery.archive_url_template' "$policy")")
license_path=$(jq -er '.source_delivery.license_path' "$policy")
license_url=$(template_value "$(jq -er '.source_delivery.license_url_template' "$policy")")
license_spdx=$(jq -er '.source_delivery.license_spdx' "$policy")

umask 077
tmp_dir=$(mktemp -d)
trap 'rm -rf -- "$tmp_dir"' EXIT
repository_json=$tmp_dir/repository.json
run_json=$tmp_dir/run.json
artifact_json=$tmp_dir/artifact.json
artifact_archive=$tmp_dir/artifact.zip
source_archive=$tmp_dir/source.tar.gz

export GH_PROMPT_DISABLED=1
gh auth token --hostname github.com >/dev/null 2>&1 || die 'no authenticated GitHub CLI session is active for github.com'
gh_api() {
  gh api --hostname github.com --method GET \
    -H 'Accept: application/vnd.github+json' \
    -H 'X-GitHub-Api-Version: 2022-11-28' "$1"
}
gh_api "repos/$policy_repository" >"$repository_json" || die 'authenticated repository metadata acquisition failed'
gh_api "repos/$policy_repository/actions/runs/$ci_run_id" >"$run_json" || die 'authenticated run metadata acquisition failed'
gh_api "repos/$policy_repository/actions/artifacts/$artifact_id" >"$artifact_json" || die 'authenticated artifact metadata acquisition failed'
for metadata_file in "$repository_json" "$run_json" "$artifact_json"; do
  jq -e . "$metadata_file" >/dev/null || die 'authenticated GitHub metadata is not valid JSON'
done

repository_id=$(jq -er '.id | numbers | tostring' "$repository_json") || die 'repository metadata id is missing'
repository_full_name=$(jq -er '.full_name' "$repository_json") || die 'repository full name is missing'
repository_url=$(jq -er '.html_url' "$repository_json") || die 'repository URL is missing'
[[ $repository_id =~ ^[1-9][0-9]*$ ]] || die 'repository ID is invalid'
[[ $repository_full_name == "$policy_repository" ]] || die 'authenticated repository full name does not match policy'
[[ $repository_url == "https://github.com/$policy_repository" ]] || die 'authenticated repository URL is not canonical'

run_id=$(jq -er '.id | numbers | tostring' "$run_json") || die 'run metadata id is missing'
run_node_id=$(jq -er '.node_id | select(type == "string" and length > 0)' "$run_json") || die 'run metadata node_id is missing'
run_number=$(jq -er '.run_number | numbers' "$run_json") || die 'run metadata run_number is missing'
run_attempt=$(jq -er '.run_attempt | numbers' "$run_json") || die 'run metadata run_attempt is missing'
run_workflow_id=$(jq -er '.workflow_id | numbers | tostring' "$run_json") || die 'run metadata workflow_id is missing'
run_check_suite_id=$(jq -er '.check_suite_id | numbers | tostring' "$run_json") || die 'run metadata check_suite_id is missing'
run_sha=$(jq -er '.head_sha' "$run_json") || die 'run metadata head_sha is missing'
run_status=$(jq -er '.status' "$run_json") || die 'run metadata status is missing'
run_conclusion=$(jq -er '.conclusion' "$run_json") || die 'run metadata conclusion is missing'
run_event=$(jq -er '.event' "$run_json") || die 'run metadata event is missing'
run_branch=$(jq -er '.head_branch' "$run_json") || die 'run metadata head_branch is missing'
run_workflow=$(jq -er '.name' "$run_json") || die 'run metadata workflow name is missing'
run_workflow_path=$(jq -er '.path' "$run_json") || die 'run metadata workflow path is missing'
run_repository=$(jq -er '.repository.full_name' "$run_json") || die 'run metadata repository is missing'
run_repository_id=$(jq -er '.repository.id | numbers | tostring' "$run_json") || die 'run repository ID is missing'
run_head_repository=$(jq -er '.head_repository.full_name' "$run_json") || die 'run head repository is missing'
run_head_repository_id=$(jq -er '.head_repository.id | numbers | tostring' "$run_json") || die 'run head repository ID is missing'
run_url=$(jq -er '.html_url' "$run_json") || die 'run metadata URL is missing'
for numeric_id in "$run_id" "$run_number" "$run_attempt" "$run_workflow_id" "$run_check_suite_id" "$run_repository_id" "$run_head_repository_id"; do
  [[ $numeric_id =~ ^[1-9][0-9]*$ ]] || die 'GitHub run metadata contains an invalid numeric ID'
done
[[ $run_id == "$ci_run_id" && $run_sha == "$source_sha" ]] || die 'run ID or head SHA does not match the request'
[[ $run_status == completed && $run_conclusion == success ]] || die 'GitHub run is not a completed success'
[[ $run_event == "$policy_event" && $run_branch == "$policy_branch" ]] || die 'GitHub run event or head branch does not match policy'
[[ $run_workflow == "$policy_workflow_name" && $run_workflow_path == "$policy_workflow_path" ]] || die 'GitHub workflow identity does not match policy'
[[ $run_repository == "$policy_repository" && $run_head_repository == "$policy_repository" ]] || die 'GitHub run repository does not match policy'
[[ $run_repository_id == "$repository_id" && $run_head_repository_id == "$repository_id" ]] || die 'GitHub run repository IDs do not match authenticated repository metadata'
[[ $run_url == "https://github.com/$policy_repository/actions/runs/$ci_run_id" ]] || die 'GitHub run URL is not canonical'

metadata_artifact_id=$(jq -er '.id | numbers | tostring' "$artifact_json") || die 'artifact metadata id is missing'
artifact_node_id=$(jq -er '.node_id | select(type == "string" and length > 0)' "$artifact_json") || die 'artifact node_id is missing'
artifact_name=$(jq -er '.name' "$artifact_json") || die 'artifact name is missing'
artifact_size=$(jq -er '.size_in_bytes | numbers' "$artifact_json") || die 'artifact size is missing'
artifact_url=$(jq -er '.url' "$artifact_json") || die 'artifact URL is missing'
artifact_archive_url=$(jq -er '.archive_download_url' "$artifact_json") || die 'artifact archive URL is missing'
artifact_expired=$(jq -r 'if (.expired | type) == "boolean" then (.expired | tostring) else error("expired is not boolean") end' "$artifact_json") || die 'artifact expired state is missing'
artifact_digest=$(jq -er '.digest | select(type == "string")' "$artifact_json") || die 'artifact digest is missing'
artifact_run_id=$(jq -er '.workflow_run.id | numbers | tostring' "$artifact_json") || die 'artifact run ID is missing'
artifact_repository_id=$(jq -er '.workflow_run.repository_id | numbers | tostring' "$artifact_json") || die 'artifact repository ID is missing'
artifact_head_repository_id=$(jq -er '.workflow_run.head_repository_id | numbers | tostring' "$artifact_json") || die 'artifact head repository ID is missing'
artifact_head_branch=$(jq -er '.workflow_run.head_branch' "$artifact_json") || die 'artifact head branch is missing'
artifact_head_sha=$(jq -er '.workflow_run.head_sha' "$artifact_json") || die 'artifact head SHA is missing'
for numeric_id in "$metadata_artifact_id" "$artifact_size" "$artifact_run_id" "$artifact_repository_id" "$artifact_head_repository_id"; do
  [[ $numeric_id =~ ^[1-9][0-9]*$ ]] || die 'GitHub artifact metadata contains an invalid numeric ID or size'
done
[[ $metadata_artifact_id == "$artifact_id" && $artifact_name == "$expected_artifact_name" ]] || die 'artifact identity does not match exact source policy'
[[ $artifact_expired == false ]] || die 'artifact metadata says the artifact is expired'
[[ $artifact_run_id == "$ci_run_id" && $artifact_head_sha == "$source_sha" && $artifact_head_branch == "$policy_branch" ]] || die 'artifact workflow run does not match the exact run head'
[[ $artifact_repository_id == "$repository_id" && $artifact_head_repository_id == "$repository_id" ]] || die 'artifact repository IDs do not match authenticated repository metadata'
expected_artifact_url="https://api.github.com/repos/$policy_repository/actions/artifacts/$artifact_id"
[[ $artifact_url == "$expected_artifact_url" && $artifact_archive_url == "$expected_artifact_url/zip" ]] || die 'artifact URLs are not canonical'

gh_api "repos/$policy_repository/actions/artifacts/$artifact_id/zip" >"$artifact_archive" || die 'authenticated artifact archive acquisition failed'
gh_api "$source_archive_api" >"$source_archive" || die 'authenticated corresponding-source acquisition failed'
[[ -s $artifact_archive && -s $source_archive ]] || die 'authenticated archive acquisition returned an empty file'

archive_sha256=$(sha256sum "$artifact_archive" | awk '{print $1}')
archive_size=$(stat -c '%s' "$artifact_archive")
[[ $artifact_digest == "sha256:$archive_sha256" ]] || die 'artifact archive digest does not match authenticated metadata'
[[ $artifact_size == "$archive_size" ]] || die 'artifact archive size does not match authenticated metadata'
unzip -tqq "$artifact_archive" >/dev/null || die 'artifact archive ZIP integrity check failed'
archive_entries_file=$tmp_dir/archive-entries.txt
unzip -Z1 "$artifact_archive" >"$archive_entries_file" || die 'could not list artifact archive'
[[ -n $(<"$archive_entries_file") ]] || die 'artifact archive is empty'
[[ $(wc -l <"$archive_entries_file") -eq $(sort -u "$archive_entries_file" | wc -l) ]] || die 'artifact archive contains duplicate entry names'
jq -r '.archive_entries[]' "$policy" | sort >"$tmp_dir/expected-entries.txt"
sort "$archive_entries_file" >"$tmp_dir/archive-entries-sorted.txt"
diff -u "$tmp_dir/expected-entries.txt" "$tmp_dir/archive-entries-sorted.txt" >/dev/null || die 'artifact archive entries do not match policy'

apk=$tmp_dir/QUIK-debug.apk
checksum_file=$tmp_dir/QUIK-debug.apk.sha256
unzip -p "$artifact_archive" QUIK-debug.apk >"$apk" || die 'could not extract exact APK entry'
unzip -p "$artifact_archive" QUIK-debug.apk.sha256 >"$checksum_file" || die 'could not extract exact checksum entry'
[[ -s $apk && -s $checksum_file ]] || die 'artifact APK or checksum is empty'
unzip -tqq "$apk" >/dev/null || die 'APK ZIP integrity check failed'
grep -Eq '^[0-9a-f]{64}  QUIK-debug\.apk$' "$checksum_file" || die 'embedded checksum format or filename is invalid'
[[ $(wc -l <"$checksum_file") -eq 1 ]] || die 'embedded checksum must contain exactly one line'
(cd -- "$tmp_dir" && sha256sum --check --strict QUIK-debug.apk.sha256 >/dev/null) || die 'embedded APK checksum verification failed'
apk_sha256=$(sha256sum "$apk" | awk '{print $1}')
[[ $(awk '{print $1}' "$checksum_file") == "$apk_sha256" ]] || die 'embedded checksum does not bind extracted APK'

tar -tzf "$source_archive" >"$tmp_dir/source-entries.txt" || die 'corresponding-source archive integrity check failed'
[[ -s $tmp_dir/source-entries.txt ]] || die 'corresponding-source archive is empty'
grep -Eq '^/|(^|/)\.\.(/|$)' "$tmp_dir/source-entries.txt" && die 'corresponding-source archive contains an unsafe path'
source_root_name=$(awk -F/ 'NF >= 2 { print $1 }' "$tmp_dir/source-entries.txt" | sort -u)
[[ -n $source_root_name && $source_root_name != *$'\n'* ]] || die 'corresponding-source archive must have one root directory'
sed "s|^$source_root_name/||" "$tmp_dir/source-entries.txt" | grep -vE '(^$|/$)' | sort -u >"$tmp_dir/source-files.txt"
git -C "$canonical_root" ls-tree -r --name-only "$source_sha" | sort -u >"$tmp_dir/git-files.txt"
diff -u "$tmp_dir/git-files.txt" "$tmp_dir/source-files.txt" >/dev/null || die 'corresponding-source archive does not contain the exact Git file set'
license_entry=$source_root_name/$license_path
[[ $(grep -Fxc "$license_entry" "$tmp_dir/source-entries.txt") -eq 1 ]] || die 'corresponding-source archive does not contain exactly one license file'
tar -xOzf "$source_archive" "$license_entry" >"$tmp_dir/source-license" || die 'could not read license from corresponding source'
git -C "$canonical_root" show "$source_sha:$license_path" >"$tmp_dir/git-license" || die 'source commit does not contain the policy license'
cmp -s "$tmp_dir/source-license" "$tmp_dir/git-license" || die 'corresponding-source license differs from exact Git source'
source_archive_sha256=$(sha256sum "$source_archive" | awk '{print $1}')
source_archive_size=$(stat -c '%s' "$source_archive")
source_file_count=$(wc -l <"$tmp_dir/source-files.txt")
license_sha256=$(sha256sum "$tmp_dir/source-license" | awk '{print $1}')

badging_file=$tmp_dir/badging.txt
permissions_file=$tmp_dir/permissions.txt
xmltree_file=$tmp_dir/xmltree.txt
signer_file=$tmp_dir/signer.txt
aapt dump badging "$apk" >"$badging_file" || die 'aapt badging failed'
aapt dump permissions "$apk" >"$permissions_file" || die 'aapt permissions failed'
aapt dump xmltree "$apk" AndroidManifest.xml >"$xmltree_file" || die 'aapt manifest dump failed'
apksigner verify --verbose --print-certs "$apk" >"$signer_file" || die 'APK signature verification failed'

package_line=$(awk '/^package: / { print; count++ } END { if (count != 1) exit 1 }' "$badging_file") || die 'aapt did not report exactly one package line'
package_name=$(sed -n "s/^package: name='\([^']*\)'.*/\1/p" <<<"$package_line")
version_code=$(sed -n "s/.* versionCode='\([^']*\)'.*/\1/p" <<<"$package_line")
version_name=$(sed -n "s/.* versionName='\([^']*\)'.*/\1/p" <<<"$package_line")
min_sdk=$(sed -n "s/^sdkVersion:'\([^']*\)'$/\1/p" "$badging_file")
target_sdk=$(sed -n "s/^targetSdkVersion:'\([^']*\)'$/\1/p" "$badging_file")
[[ $package_name == "$(jq -er '.package.name' "$policy")" && $version_code == "$(jq -er '.package.version_code' "$policy")" && $version_name == "$(jq -er '.package.version_name' "$policy")" ]] || die 'APK package or version does not match policy'
[[ $min_sdk == "$(jq -er '.package.min_sdk' "$policy")" && $target_sdk == "$(jq -er '.package.target_sdk' "$policy")" ]] || die 'APK SDK bounds do not match policy'
[[ $(grep -c '^application-debuggable$' "$badging_file") -eq 1 ]] || die 'APK is not marked exactly once as debuggable'

signer_count=$(awk '/^Signer #[0-9]+ certificate SHA-256 digest:/ { count++ } END { print count + 0 }' "$signer_file")
[[ $signer_count == 1 ]] || die "expected exactly one signer certificate, got $signer_count"
signer_digest=$(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' "$signer_file")
signer_digest=${signer_digest//:/}
signer_digest=${signer_digest,,}
[[ $signer_digest =~ ^[0-9a-f]{64}$ ]] || die 'signer certificate SHA-256 digest has an invalid format'

permissions_actual=$tmp_dir/permissions-actual.txt
declared_permissions_actual=$tmp_dir/declared-permissions-actual.txt
awk -F"'" '/^uses-permission: name=/ { print $2 }' "$permissions_file" | sort -u >"$permissions_actual"
awk -F': ' '/^permission: / { print $2 }' "$permissions_file" | sort -u >"$declared_permissions_actual"

manifest_semantics=$tmp_dir/manifest-semantics.txt
awk '
  function indentation(text, prefix) { prefix = text; sub(/[^ ].*$/, "", prefix); return length(prefix) }
  function attribute_value(text, value) {
    value = text; sub(/^[^=]*=/, "", value)
    if (value ~ /^"/) { sub(/^"/, "", value); sub(/".*$/, "", value); return value }
    if (value ~ /\(Raw: "/) { sub(/^.*\(Raw: "/, "", value); sub(/"\).*$/, "", value); return value }
    return value
  }
  function clear_values(key) {
    for (key in actions) delete actions[key]
    for (key in categories) delete categories[key]
    for (key in schemes) delete schemes[key]
    for (key in mime_types) delete mime_types[key]
  }
  function emit_component(key, value, parts) {
    if (component_kind == "" || component_name == "" || !exported) return
    key = component_kind ":" component_name
    print "component|" key
    print "permission|" key "=" component_permission
    print "filter-count|" key "=" filter_count
    for (value in actions) { split(value, parts, SUBSEP); print "filter-action|" key "|" parts[1] "=" parts[2] }
    for (value in categories) { split(value, parts, SUBSEP); print "filter-category|" key "|" parts[1] "=" parts[2] }
    for (value in schemes) { split(value, parts, SUBSEP); print "filter-scheme|" key "|" parts[1] "=" parts[2] }
    for (value in mime_types) { split(value, parts, SUBSEP); print "filter-mime|" key "|" parts[1] "=" parts[2] }
  }
  /^[[:space:]]*E: (activity|activity-alias|receiver|service|provider)([[:space:]]|$)/ {
    emit_component(); clear_values(); component_indent = indentation($0); component_kind = $0
    sub(/^[[:space:]]*E: /, "", component_kind); sub(/[[:space:]].*$/, "", component_kind)
    component_name = ""; component_permission = ""; exported = 0; filter_count = 0; current_filter = 0; subelement = ""; next
  }
  component_kind != "" && /^[[:space:]]*E: / {
    current_indent = indentation($0)
    if (current_indent <= component_indent) { emit_component(); clear_values(); component_kind = ""; next }
    element = $0; sub(/^[[:space:]]*E: /, "", element); sub(/[[:space:]].*$/, "", element)
    if (element == "intent-filter") { filter_count++; current_filter = filter_count; subelement = "" }
    else if (current_filter > 0 && (element == "action" || element == "category" || element == "data")) subelement = element
    else { current_filter = 0; subelement = "other" }
    next
  }
  component_kind != "" && /^[[:space:]]*A: android:name/ {
    value = attribute_value($0)
    if (current_filter == 0 && subelement == "") component_name = value
    else if (current_filter > 0 && subelement == "action") actions[current_filter, value] = 1
    else if (current_filter > 0 && subelement == "category") categories[current_filter, value] = 1
    next
  }
  component_kind != "" && current_filter == 0 && subelement == "" && /^[[:space:]]*A: android:permission/ { component_permission = attribute_value($0); next }
  component_kind != "" && current_filter == 0 && subelement == "" && /^[[:space:]]*A: android:exported/ {
    value = attribute_value($0); if (value == "true" || value ~ /0xffffffff$/) exported = 1; next
  }
  component_kind != "" && current_filter > 0 && subelement == "data" && /^[[:space:]]*A: android:scheme/ { schemes[current_filter, attribute_value($0)] = 1; next }
  component_kind != "" && current_filter > 0 && subelement == "data" && /^[[:space:]]*A: android:mimeType/ { mime_types[current_filter, attribute_value($0)] = 1; next }
  END { emit_component() }
' "$xmltree_file" >"$manifest_semantics"

compare_policy_set() {
  local policy_key=$1 actual_file=$2 label=$3
  jq -r --arg key "$policy_key" '.[$key][]' "$policy" | sort -u >"$tmp_dir/expected-set.txt"
  sort -u "$actual_file" >"$tmp_dir/actual-set.txt"
  diff -u "$tmp_dir/expected-set.txt" "$tmp_dir/actual-set.txt" >/dev/null || die "$label differ from policy"
}
compare_policy_set allowed_permissions "$permissions_actual" 'APK permissions'
compare_policy_set declared_permissions "$declared_permissions_actual" 'APK declared permissions'
awk -F'|' '$1 == "component" { print $2 }' "$manifest_semantics" >"$tmp_dir/components.txt"
awk -F'|' '$1 == "permission" { print $2 }' "$manifest_semantics" >"$tmp_dir/component-permissions.txt"
compare_policy_set allowed_exported_components "$tmp_dir/components.txt" 'exported components'
compare_policy_set allowed_exported_component_permissions "$tmp_dir/component-permissions.txt" 'exported component permission guards'

jq -r '
  .allowed_exported_intent_filters[] as $component |
  "filter-count|\($component.component)=\($component.filters | length)",
  ($component.filters | to_entries[] as $filter |
    ($filter.value.actions[] | "filter-action|\($component.component)|\($filter.key + 1)=\(.)"),
    ($filter.value.categories[] | "filter-category|\($component.component)|\($filter.key + 1)=\(.)"),
    ($filter.value.data_schemes[] | "filter-scheme|\($component.component)|\($filter.key + 1)=\(.)"),
    ($filter.value.data_mime_types[] | "filter-mime|\($component.component)|\($filter.key + 1)=\(.)")
  )
' "$policy" | sort -u >"$tmp_dir/expected-filters.txt"
grep -E '^filter-(count|action|category|scheme|mime)\|' "$manifest_semantics" | sort -u >"$tmp_dir/actual-filters.txt"
diff -u "$tmp_dir/expected-filters.txt" "$tmp_dir/actual-filters.txt" >/dev/null || die 'exported intent-filter boundaries or contents differ from policy'

permissions_json=$(jq -Rsc 'split("\n") | map(select(length > 0))' "$permissions_actual")
declared_permissions_json=$(jq -Rsc 'split("\n") | map(select(length > 0))' "$declared_permissions_actual")
sensitive_permissions_json=$(jq -Rsc --argjson sensitive "$(jq -c '.allowed_sensitive_permissions' "$policy")" 'split("\n") | map(select(length > 0)) as $actual | $sensitive | map(select(. as $item | $actual | index($item)))' "$permissions_actual")
exported_components_json=$(sort -u "$tmp_dir/components.txt" | jq -Rsc 'split("\n") | map(select(length > 0))')
component_permissions_json=$(sort -u "$tmp_dir/component-permissions.txt" | jq -Rsc 'split("\n") | map(select(length > 0))')
intent_filters_json=$(jq -c '.allowed_exported_intent_filters' "$policy")
archive_entries_json=$(jq -c '.archive_entries' "$policy")
apk_size=$(stat -c '%s' "$apk")
repository_metadata_sha256=$(sha256sum "$repository_json" | awk '{print $1}')
run_metadata_sha256=$(sha256sum "$run_json" | awk '{print $1}')
artifact_metadata_sha256=$(sha256sum "$artifact_json" | awk '{print $1}')
policy_sha256=$(sha256sum "$policy" | awk '{print $1}')
schema_sha256=$(sha256sum "$receipt_schema" | awk '{print $1}')
first_line() { sed -n '1p'; }
git_version=$(git --version | first_line)
gh_version=$(gh version | first_line)
jq_version=$(jq --version | first_line)
unzip_version=$(unzip -v | first_line)
tar_version=$(tar --version | first_line)
aapt_version=$(aapt version 2>&1 | first_line)
apksigner_version=$(apksigner version 2>&1 | first_line)
sha256sum_version=$(sha256sum --version | first_line)
stat_version=$(stat --version | first_line)

receipt_tmp=$tmp_dir/receipt.json
jq -n \
  --arg schema_id "$schema_id" --argjson schema_version "$schema_version" --arg schema_sha256 "$schema_sha256" \
  --arg repository "$policy_repository" --arg repository_id "$repository_id" --arg repository_url "$repository_url" --arg repository_metadata_sha256 "$repository_metadata_sha256" \
  --arg source_sha "$source_sha" --arg source_ref "$policy_source_ref" --arg source_commit_url "$source_commit_url" \
  --arg source_archive_api "$source_archive_api" --arg source_archive_url "$source_archive_url" --arg source_archive_sha256 "$source_archive_sha256" --argjson source_archive_size "$source_archive_size" --argjson source_file_count "$source_file_count" \
  --arg license_path "$license_path" --arg license_url "$license_url" --arg license_sha256 "$license_sha256" --arg license_spdx "$license_spdx" \
  --arg policy_sha256 "$policy_sha256" \
  --arg run_id "$ci_run_id" --arg run_node_id "$run_node_id" --argjson run_number "$run_number" --argjson run_attempt "$run_attempt" --arg run_workflow_id "$run_workflow_id" --arg run_check_suite_id "$run_check_suite_id" --arg run_url "$run_url" --arg run_event "$run_event" --arg run_branch "$run_branch" --arg run_workflow "$run_workflow" --arg run_workflow_path "$run_workflow_path" --arg run_metadata_sha256 "$run_metadata_sha256" \
  --arg artifact_id "$artifact_id" --arg artifact_node_id "$artifact_node_id" --arg artifact_name "$artifact_name" --arg artifact_url "$artifact_url" --arg artifact_archive_url "$artifact_archive_url" --argjson artifact_size "$artifact_size" --arg artifact_digest "$artifact_digest" --arg artifact_metadata_sha256 "$artifact_metadata_sha256" \
  --arg archive_sha256 "$archive_sha256" --argjson archive_size "$archive_size" --argjson archive_entries "$archive_entries_json" \
  --arg apk_sha256 "$apk_sha256" --argjson apk_size "$apk_size" --arg package_name "$package_name" --arg version_code "$version_code" --arg version_name "$version_name" --arg min_sdk "$min_sdk" --arg target_sdk "$target_sdk" --arg signer_digest "$signer_digest" --arg signer_mode "$(jq -er '.signer.mode' "$policy")" --arg signer_limitation "$(jq -er '.signer.limitation' "$policy")" \
  --argjson permissions "$permissions_json" --argjson declared_permissions "$declared_permissions_json" --argjson sensitive_permissions "$sensitive_permissions_json" --argjson exported_components "$exported_components_json" --argjson component_permissions "$component_permissions_json" --argjson intent_filters "$intent_filters_json" \
  --arg git_version "$git_version" --arg gh_version "$gh_version" --arg jq_version "$jq_version" --arg unzip_version "$unzip_version" --arg tar_version "$tar_version" --arg aapt_version "$aapt_version" --arg apksigner_version "$apksigner_version" --arg sha256sum_version "$sha256sum_version" --arg stat_version "$stat_version" \
  '{
    receipt_type: "only21mil.quik.debug-apk-provenance", schema_version: $schema_version,
    schema: {id: $schema_id, sha256: $schema_sha256}, result: "pass", repository: $repository,
    acquisition: {provider: "github.com", tool: "gh", hostname_pinned: true, authenticated: true, caller_supplied_evidence: false},
    github_repository: {id: $repository_id, full_name: $repository, url: $repository_url, metadata_sha256: $repository_metadata_sha256},
    source: {
      kind: "git-head", commit: $source_sha, ref: $source_ref, git_clean: true,
      relation: "checkout-head-equals-github-run-head", commit_url: $source_commit_url,
      archive: {api_path: $source_archive_api, url: $source_archive_url, sha256: $source_archive_sha256, size_bytes: $source_archive_size, file_count: $source_file_count, exact_git_file_set: true},
      license: {path: $license_path, url: $license_url, sha256: $license_sha256, spdx: $license_spdx, exact_git_blob: true}
    },
    policy: {schema_version: 3, validator_version: 3, sha256: $policy_sha256},
    github_run: {id: $run_id, node_id: $run_node_id, run_number: $run_number, run_attempt: $run_attempt, workflow_id: $run_workflow_id, check_suite_id: $run_check_suite_id, head_sha: $source_sha, status: "completed", conclusion: "success", event: $run_event, head_branch: $run_branch, workflow_name: $run_workflow, workflow_path: $run_workflow_path, repository_id: $repository_id, url: $run_url, metadata_sha256: $run_metadata_sha256},
    github_artifact: {id: $artifact_id, node_id: $artifact_node_id, name: $artifact_name, size_in_bytes: $artifact_size, digest: $artifact_digest, expired: false, url: $artifact_url, archive_download_url: $artifact_archive_url, workflow_run_id: $run_id, head_sha: $source_sha, repository_id: $repository_id, metadata_sha256: $artifact_metadata_sha256},
    archive: {filename: "artifact.zip", sha256: $archive_sha256, size_bytes: $archive_size, zip_integrity: "pass", entries: $archive_entries, embedded_checksum: "pass"},
    apk: {filename: "QUIK-debug.apk", sha256: $apk_sha256, size_bytes: $apk_size, zip_integrity: "pass", package_name: $package_name, debuggable: true, version_code: $version_code, version_name: $version_name, min_sdk: $min_sdk, target_sdk: $target_sdk,
      signer: {certificate_sha256: $signer_digest, mode: $signer_mode, expected_certificate_sha256: null, identity_bound: false, limitation: $signer_limitation},
      permissions: $permissions, declared_permissions: $declared_permissions, sensitive_permissions: $sensitive_permissions, exported_components: $exported_components, exported_component_permissions: $component_permissions, exported_intent_filters: $intent_filters},
    delivery: {result: "complete", artifact: "authenticated-exact-archive", corresponding_source: "authenticated-exact-commit-archive", license: $license_spdx},
    tools: {git: $git_version, gh: $gh_version, jq: $jq_version, unzip: $unzip_version, tar: $tar_version, aapt: $aapt_version, apksigner: $apksigner_version, sha256sum: $sha256sum_version, stat: $stat_version}
  }' >"$receipt_tmp"

if [[ $receipt == /dev/stdout ]]; then
  cat "$receipt_tmp"
else
  [[ ! -L $receipt ]] || die 'receipt path must not be a symlink'
  install -m 0644 "$receipt_tmp" "$receipt"
fi
