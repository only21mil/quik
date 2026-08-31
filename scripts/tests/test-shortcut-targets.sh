#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/../.." && pwd)"

if [[ "${SKIP_SHORTCUT_BUILD:-0}" != "1" ]]; then
  "$repo_root/gradlew" --offline \
    :presentation:processDebugResources \
    :presentation:processReleaseResources \
    :presentation:processFdroidResources >/dev/null
fi

aapt2_path="${AAPT2:-}"
if [[ -z "$aapt2_path" ]]; then
  android_sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}}"
  aapt2_path="$(find "$android_sdk_root/build-tools" -mindepth 2 -maxdepth 2 \
    -type f -name aapt2 -print | sort -V | tail -n 1)"
fi

if [[ ! -x "$aapt2_path" ]]; then
  echo "aapt2 is not executable: $aapt2_path" >&2
  exit 1
fi

check_variant() {
  local variant="$1"
  local expected_package="$2"
  local archive="$repo_root/presentation/build/intermediates/processed_res/$variant/out/resources-$variant.ap_"
  local xml_tree

  if [[ ! -f "$archive" ]]; then
    echo "compiled resources are missing for $variant: $archive" >&2
    exit 1
  fi

  xml_tree="$($aapt2_path dump xmltree "$archive" --file res/xml/shortcuts.xml)"
  mapfile -t intent_lines < <(grep 'E: intent ' <<<"$xml_tree")
  mapfile -t target_lines < <(grep 'android:targetPackage' <<<"$xml_tree")
  mapfile -t class_lines < <(grep 'android:targetClass' <<<"$xml_tree")
  mapfile -t action_lines < <(grep 'android:action' <<<"$xml_tree")

  if (( ${#intent_lines[@]} != 1 || ${#target_lines[@]} != 1 || ${#class_lines[@]} != 1 || ${#action_lines[@]} != 1 )); then
    echo "$variant shortcut must compile exactly one complete intent" >&2
    echo "$xml_tree" >&2
    exit 1
  fi
  if [[ "${target_lines[0]}" != *"=\"$expected_package\" (Raw: \"$expected_package\")"* ]]; then
    echo "$variant shortcut does not compile the literal package $expected_package" >&2
    echo "${target_lines[0]}" >&2
    exit 1
  fi
  if [[ "${target_lines[0]}" == *"=@0x"* ]]; then
    echo "$variant shortcut targetPackage compiled as a resource reference" >&2
    exit 1
  fi
  if [[ "${action_lines[0]}" != *'="android.intent.action.MAIN" (Raw: "android.intent.action.MAIN")'* ]]; then
    echo "$variant shortcut action is not MAIN" >&2
    exit 1
  fi
  if [[ "${class_lines[0]}" != *'="dev.octoshrimpy.quik.feature.compose.ComposeActivity" (Raw: "dev.octoshrimpy.quik.feature.compose.ComposeActivity")'* ]]; then
    echo "$variant shortcut does not target ComposeActivity" >&2
    exit 1
  fi
}

check_variant debug io.github.only21mil.quik.reactions.debug
check_variant release io.github.only21mil.quik.reactions
check_variant fdroid io.github.only21mil.quik.reactions.fdroid

source_verifier="$repo_root/scripts/verify-shortcut-source.py"
python3 "$source_verifier" "$repo_root/presentation/src/debug/res/xml/shortcuts.xml" io.github.only21mil.quik.reactions.debug
if python3 "$source_verifier" "$script_dir/fixtures/shortcuts-extra-action.xml" io.github.only21mil.quik.reactions.debug 2>/dev/null; then
  echo "shortcut source verifier accepted an additive arbitrary action" >&2
  exit 1
fi
