# Fork identity

The fork uses `io.github.only21mil.quik.reactions` as its release application ID. Debug and F-Droid builds append `.debug` and `.fdroid`. Manifest authorities derive from `${applicationId}`, and each shortcut targets the matching variant ID.

The launcher label identifies the installed build:

- release: `QUIK Reactions`
- debug: `QUIK Reactions Debug`
- F-Droid: `QUIK Reactions F-Droid`

The dark launcher icon keeps the speech bubble and adds an orange badge with a dark heart cutout. The heart uses `#050505` against `#FF9F0A`, a contrast ratio above 3:1, so the distinguishing detail does not depend on the lower-contrast cream/orange pairing. Adaptive icons use the same artwork on Android 8 and newer. A vector legacy icon covers the supported Android 6 and 7 releases. Asset provenance and license attribution are recorded in [third-party-assets.md](third-party-assets.md).

## Version policy

Set `versionCode` to `upstreamCode * 1000 + forkRevision`. Fork revisions start at 1 for each upstream code and may run through 999. The first fork build based on upstream code 2238 is therefore 2238001. This is newer than the existing fork package at code 2238, and the mapping stays monotonic when either upstream or fork revision advances.

Use `upstreamVersion-reactions.forkRevision` for `versionName`. Variant suffixes follow it, such as `4.3.6-reactions.1-debug` and `4.3.6-reactions.1-fdroid`. Display versions describe provenance; Android uses `versionCode` to decide upgrade order.
