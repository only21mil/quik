#!/usr/bin/env bash
set -euo pipefail

repo_root="$(git rev-parse --show-toplevel)"
cd "$repo_root"

expected_workflows=(
  .github/workflows/android-main.yml
  .github/workflows/android-pr.yml
)
mapfile -t actual_workflows < <(
  find .github/workflows -maxdepth 1 -type f \
    \( -name '*.yml' -o -name '*.yaml' \) -print | sort
)

if [[ "${actual_workflows[*]}" != "${expected_workflows[*]}" ]]; then
  echo "Expected only the reviewed Android PR and main workflows." >&2
  printf 'Expected: %s\n' "${expected_workflows[*]}" >&2
  printf 'Actual:   %s\n' "${actual_workflows[*]}" >&2
  exit 1
fi

expected_workflow_hashes=(
  '2ddc2e26aafb224e81f07dec3c1a2a68b7e1a0a06968b5875b72f140cdc318db  .github/workflows/android-main.yml'
  '2c3b3777d652942605776664ee7bdd8fe17659b4260c8eedeedeeda7618eb572  .github/workflows/android-pr.yml'
)
if ! printf '%s\n' "${expected_workflow_hashes[@]}" | sha256sum --check --status; then
  echo 'An Android workflow does not match its reviewed full-file SHA-256.' >&2
  sha256sum "${expected_workflows[@]}" >&2
  exit 1
fi

workflow_files=("${actual_workflows[@]}")
expected_main_header=$'name: Android main\n\non:\n  push:\n    branches:\n      - main'
expected_pr_header=$'name: Android PR\n\non:\n  pull_request:\n    branches:\n      - main'

[[ "$(sed -n '1,6p' .github/workflows/android-main.yml)" == "$expected_main_header" ]]
[[ "$(sed -n '1,6p' .github/workflows/android-pr.yml)" == "$expected_pr_header" ]]

for workflow in "${workflow_files[@]}"; do
  grep -Fxq 'permissions:' "$workflow"
  grep -Fxq '  contents: read' "$workflow"
  grep -Fq 'persist-credentials: false' "$workflow"

  unpinned_actions="$(
    sed -nE 's/^[[:space:]]*uses:[[:space:]]*([^#[:space:]]+).*/\1/p' "$workflow" |
      grep -Ev '@[0-9a-f]{40}$' || true
  )"
  if [[ -n "$unpinned_actions" ]]; then
    echo "Unpinned actions in $workflow:" >&2
    printf '%s\n' "$unpinned_actions" >&2
    exit 1
  fi
done

forbidden_pattern='workflow_dispatch:|workflow_call:|repository_dispatch:|schedule:|release:|deployment_status:|page_build:|environment:|secrets\.|[[:alnum:]_-]+:[[:space:]]*write|keystore|signing|assembleRelease|assembleFdroid|bundleRelease|action-gh-release|actions-gh-pages|create-pull-request|fastlane|fdroid|apksigner|jarsigner|git[[:space:]]+push|gh[[:space:]]+(api|release)|curl|wget'
if grep -RniE "$forbidden_pattern" "${workflow_files[@]}"; then
  echo 'A workflow contains a release, deploy, signing, secret, or write path.' >&2
  exit 1
fi

main_workflow=.github/workflows/android-main.yml
pr_workflow=.github/workflows/android-pr.yml

extract_run_commands() {
  awk '
    function indentation(value, first_non_space) {
      first_non_space = match(value, /[^ ]/)
      return first_non_space == 0 ? length(value) : first_non_space - 1
    }
    function emit(value) {
      sub(/^[[:space:]]+/, "", value)
      if (value != "" && value !~ /^#/) {
        print value
      }
    }
    {
      line_indent = indentation($0)
      if (in_block) {
        if ($0 ~ /^[[:space:]]*$/) {
          next
        }
        if (line_indent > run_indent) {
          emit($0)
          next
        }
        in_block = 0
      }

      line = $0
      sub(/^[[:space:]]+/, "", line)
      if (line ~ /^run:[[:space:]]*/) {
        run_indent = line_indent
        sub(/^run:[[:space:]]*/, "", line)
        if (line ~ /^[|>][-+]?[[:space:]]*(#.*)?$/) {
          in_block = 1
          next
        }
        emit(line)
      }
    }
  ' "$1"
}

assert_reviewed_gradle_inventory() {
  local workflow="$1"
  local line index
  local reviewed_path_case='android-smsmms/*|common/*|data/*|domain/*|presentation/*|scripts/check-merged-manifest-authority.py|scripts/fixtures/merged-manifest-authority-negative.json|scripts/tests/test-release-config.sh|gradle/*|build.gradle|settings.gradle|gradle.properties|gradlew|gradlew.bat|.github/workflows/android-pr.yml|.github/workflows/android-main.yml)'
  local -a run_commands actual_gradle_commands=()
  local -a expected_gradle_commands=(
    './gradlew --no-daemon --stacktrace assembleDebug'
    './gradlew --no-daemon --stacktrace assembleDebugAndroidTest'
    './gradlew --no-daemon --stacktrace :presentation:checkMergedManifestAuthority'
    './gradlew --no-daemon --stacktrace lint'
    './gradlew --no-daemon --stacktrace test'
  )

  mapfile -t run_commands < <(extract_run_commands "$workflow")
  for line in "${run_commands[@]}"; do
    if [[ "$line" == "$reviewed_path_case" ]]; then
      continue
    fi
    if [[ "$line" == *gradlew* ]]; then
      actual_gradle_commands+=("$line")
    elif [[ "$line" == *gradle* ]]; then
      echo "A workflow invokes Gradle directly instead of the reviewed wrapper in $workflow: $line" >&2
      exit 1
    fi
  done

  if (( ${#actual_gradle_commands[@]} != ${#expected_gradle_commands[@]} )); then
    echo "Expected exactly five reviewed Gradle wrapper commands in $workflow." >&2
    printf 'Actual: %s\n' "${actual_gradle_commands[@]}" >&2
    exit 1
  fi
  for index in "${!expected_gradle_commands[@]}"; do
    if [[ "${actual_gradle_commands[index]}" != "${expected_gradle_commands[index]}" ]]; then
      echo "Unexpected Gradle wrapper command or order in $workflow." >&2
      printf 'Expected: %s\n' "${expected_gradle_commands[index]}" >&2
      printf 'Actual:   %s\n' "${actual_gradle_commands[index]}" >&2
      exit 1
    fi
  done
}

assert_exact_release_guard_block() {
  local workflow="$1" expected_block="$2"
  local workflow_content

  workflow_content="$(<"$workflow")"
  if [[ "$workflow_content" != *"$expected_block"* ]]; then
    echo "Expected the exact reviewed assembleDebug and release-guard step block in $workflow." >&2
    exit 1
  fi
}

expected_main_release_guard_block=$'      - name: Assemble debug APK\n        run: ./gradlew --no-daemon --stacktrace assembleDebug\n\n      - name: Test release configuration guard\n        run: bash scripts/tests/test-release-config.sh'
expected_pr_release_guard_block=$'      - name: Assemble debug APK\n        if: steps.paths.outputs.android == \'true\'\n        run: ./gradlew --no-daemon --stacktrace assembleDebug\n\n      - name: Test release configuration guard\n        if: steps.paths.outputs.android == \'true\'\n        run: bash scripts/tests/test-release-config.sh'

assert_reviewed_gradle_inventory "$main_workflow"
assert_reviewed_gradle_inventory "$pr_workflow"
assert_exact_release_guard_block "$main_workflow" "$expected_main_release_guard_block"
assert_exact_release_guard_block "$pr_workflow" "$expected_pr_release_guard_block"

[[ "$(grep -Fc 'actions/upload-artifact@' "$main_workflow")" -eq 1 ]]
[[ "$(grep -Fc 'actions/upload-artifact@' "$pr_workflow")" -eq 0 ]]
grep -Fq 'cache-read-only: true' "$main_workflow"
grep -Fq 'cache-read-only: true' "$pr_workflow"

if find fdroid releases -type f -print -quit 2>/dev/null | grep -q .; then
  echo 'A legacy F-Droid metadata or release payload is still tracked.' >&2
  exit 1
fi

echo 'Workflow safety policy passed.'
