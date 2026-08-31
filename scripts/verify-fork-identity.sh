#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

./gradlew --offline \
  :presentation:processDebugResources :presentation:processDebugMainManifest \
  :presentation:processReleaseResources :presentation:processReleaseMainManifest \
  :presentation:processFdroidResources :presentation:processFdroidMainManifest

declare -A packages=(
  [debug]='io.github.only21mil.quik.reactions.debug'
  [release]='io.github.only21mil.quik.reactions'
  [fdroid]='io.github.only21mil.quik.reactions.fdroid'
)
declare -A labels=(
  [debug]='QUIK Reactions Debug'
  [release]='QUIK Reactions'
  [fdroid]='QUIK Reactions F-Droid'
)
declare -A versions=(
  [debug]='4.3.6-reactions.1-debug'
  [release]='4.3.6-reactions.1'
  [fdroid]='4.3.6-reactions.1-fdroid'
)

aapt2="$(find "${ANDROID_HOME:-$HOME/Android/Sdk}/build-tools" -mindepth 2 -maxdepth 2 -type f -name aapt2 -print | sort -V | tail -n 1)"
test -x "$aapt2"

for variant in debug release fdroid; do
  manifest="presentation/build/intermediates/merged_manifests/$variant/AndroidManifest.xml"
  package_file="presentation/build/intermediates/packaged_manifests/$variant/AndroidManifest.xml"
  resource_ap="presentation/build/intermediates/processed_res/$variant/out/resources-${variant}.ap_"

  test -f "$manifest"
  rg -q "package=\"${packages[$variant]}\"" "$manifest"
  rg -q 'android:versionCode="2238001"' "$manifest"
  rg -q "android:versionName=\"${versions[$variant]}\"" "$manifest"
  rg -q 'android:icon="@mipmap/ic_launcher_reactions"' "$manifest"
  rg -q 'android:roundIcon="@mipmap/ic_launcher_reactions_round"' "$manifest"
  rg -q "android:authorities=\"${packages[$variant]}\.androidx-startup\"" "$manifest"
  rg -q "android:authorities=\"${packages[$variant]}\.mmspart\"" "$manifest"
  rg -q "android:authorities=\"${packages[$variant]}\.messagesText\"" "$manifest"
  rg -q "android:authorities=\"${packages[$variant]}\.MmsFileProvider\"" "$manifest"

  shortcut="presentation/src/$variant/res/xml/shortcuts.xml"
  [[ "$variant" == release ]] && shortcut="presentation/src/main/res/xml/shortcuts.xml"
  python3 scripts/verify-shortcut-source.py "$shortcut" "${packages[$variant]}"

  test -f "$resource_ap"
  resource_dump="$($aapt2 dump resources "$resource_ap")"
  grep -Fq 'mipmap/ic_launcher_reactions' <<<"$resource_dump"
  grep -Fq 'mipmap/ic_launcher_reactions_round' <<<"$resource_dump"
  grep -Fq "${labels[$variant]}" <<<"$resource_dump"

  test -f "$package_file"
done

echo "fork identity checks passed for debug, release, and F-Droid"
