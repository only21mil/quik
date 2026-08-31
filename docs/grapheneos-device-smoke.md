# GrapheneOS device smoke protocol

## Status and authority

This file is a plan. It does not authorize a device action.

Host-only artifact inspection may run before approval. Stop before connecting
ADB or touching a phone unless the device owner gives explicit approval for
this exact run. The approval record must name:

- the device and GrapheneOS user profile
- the APK SHA-256 and signer
- the people and devices allowed to exchange test traffic
- the allowed SMS, MMS, reaction add, and reaction remove actions
- any ADB connection, profile creation, installation, permission, or default
  SMS role change
- any SIM, airplane-mode, mobile-data, or Wi-Fi change
- app restart or force-stop actions used for the late-callback case
- diagnostic collection, if separately needed after a failure
- uninstall, SMS-role restoration, permission removal, and profile deletion
- the start time and rollback deadline

Rachel and Mason must each consent before a case sends traffic to their
devices. Every other group participant must also consent. A build, review, or
this document cannot provide that approval.

Permission removal and SMS-role restoration need their own approval entries.
Approval to grant a permission or change the role does not approve the later
cleanup action.

No command in this protocol may run automatically. A human performs each send,
reaction, role change, radio change, and cleanup action on the device. Do not
use ADB to compose or send SMS or MMS, invoke a reaction, grant the SMS role,
grant permissions, change a radio, force-stop the app, or delete a profile.

## Artifact contract

The candidate for this protocol has this exact debug identity:

- application ID: `io.github.only21mil.quik.reactions.debug`
- version code: `2238001`
- version name: `4.3.6-reactions.1-debug`
- launcher label: `QUIK Reactions Debug`

The release application ID is `io.github.only21mil.quik.reactions`. This
protocol does not test or authorize a release build.

The device under test is `DUT`. Peer aliases are `RACHEL` and `MASON`. Evidence
must not contain phone numbers, addresses, contact exports, account data,
private message text, or a raw reaction control message. Use new opaque markers
such as `QK-SMS-<nonce>` and record only their aliases.

Reaction traffic is carrier SMS or MMS. It may cost money, roam, arrive late,
or appear as ordinary text in another client. SMS and MMS are not end-to-end
encrypted by this app.

## Phase 0: host-only preflight

Set the path to an immutable candidate. Do not use a symlink or a mutable
`latest.apk` path.

```sh
QUIK_APK=/absolute/path/to/QUIK-debug.apk
test -f "$QUIK_APK"
test ! -L "$QUIK_APK"
sha256sum "$QUIK_APK"
file "$QUIK_APK"
```

Use the installed Android SDK tools to read the manifest and signature:

```sh
APK_ANALYZER=/absolute/path/to/apkanalyzer
APK_SIGNER=/absolute/path/to/apksigner
AAPT2=/absolute/path/to/aapt2
"$APK_ANALYZER" manifest application-id "$QUIK_APK"
"$APK_ANALYZER" manifest version-code "$QUIK_APK"
"$APK_ANALYZER" manifest version-name "$QUIK_APK"
"$AAPT2" dump badging "$QUIK_APK"
"$APK_SIGNER" verify --verbose --print-certs "$QUIK_APK"
```

Confirm the exact application ID, version code, and version name listed above.
In the `aapt2 dump badging` output, confirm the launchable application label is
exactly `QUIK Reactions Debug`. Confirm that the APK is debuggable, has the
expected debug signer, and is eligible for the Android SMS role. Record the
label, digest, and signer fingerprint in the private run sheet. Stop on any
mismatch. A similar filename is not proof.

The remaining phases require the recorded device approval.

## Phase 1: approved device preflight

Create a disposable GrapheneOS secondary user only if approval includes profile
creation. Enable calls and SMS only if the approved device supports them in the
secondary user. Do not add an account, contacts sync, cloud backup, or SIM
management account. Stop if the profile cannot send and receive both SMS and
MMS. Moving the run to the owner profile requires a new approval.

Connect ADB only if the approval names that connection. Read back the device,
current user, GrapheneOS build, package state, and existing SMS role holder:

```sh
adb devices -l
QUIK_TEST_USER_ID="$(adb shell am get-current-user | tr -d '\r')"
test -n "$QUIK_TEST_USER_ID"
adb shell getprop ro.build.fingerprint
adb shell getprop ro.build.version.security_patch
adb shell cmd role get-role-holders --user "$QUIK_TEST_USER_ID" android.app.role.SMS
adb shell pm path --user "$QUIK_TEST_USER_ID" io.github.only21mil.quik.reactions.debug
```

An empty or multiple SMS-role result is a stop condition. Record the original
holder exactly. Check the owner profile separately and record its holder. It
must not change during the run.

Do not query the SMS, MMS, contacts, notification, or telephony providers with
ADB. Do not collect a bug report, full logcat, provider dump, database, or
contact list.

### Thread and route gate

The approved test profile must already contain these provider-backed threads:

- one one-to-one SMS thread with an approved peer
- one one-to-one, text-only MMS thread with an approved peer
- one true group-MMS thread containing only approved participants

Do not create, rename, or change the membership of the group for this test.
Confirm on every peer device that group membership agrees. The group must be
group MMS, not mass text or separate SMS copies. Stop on a stale address,
duplicate identity, unexpected participant, unexpected history, or ambiguous
route.

### Dual-SIM gate

Use GrapheneOS Settings to record only `single-SIM`, `SIM-A`, or `SIM-B`. Never
record an ICCID, IMSI, phone number, account number, or eSIM credential.

For two active SIMs, record the subscription alias already attached to every
approved thread. QUIK must display one unambiguous route before a send. Do not
switch a thread's SIM to gain coverage. Do not proceed through a roaming,
international-charge, or carrier-confirmation prompt.

If both SIMs have approved one-to-one threads, run `DS-A` and `DS-B`. Run the
group cases once on the group's existing route.

## Phase 2: approved install, role, and initial state

The install, app launch, permission prompts, and SMS-role change are separate
approved device mutations. Confirm the debug package is absent in both the
test and owner profiles. If it is present, stop. This protocol covers a fresh
install, not an upgrade.

An approved human may run this exact install command:

```sh
adb install --user "$QUIK_TEST_USER_ID" --no-streaming "$QUIK_APK"
```

Do not add `-r` or `-d`. Read back the installed package and versions:

```sh
adb shell pm path --user "$QUIK_TEST_USER_ID" io.github.only21mil.quik.reactions.debug
adb shell dumpsys package io.github.only21mil.quik.reactions.debug | sed -n '/versionCode=/p;/versionName=/p'
```

Inspect the launcher before opening the app. The visible label must be exactly
`QUIK Reactions Debug`. Record the observed label and stop if it differs. Launch
the app by tapping that icon. Use the visible Android role prompt to make it the
default SMS app. Grant only the runtime permissions required for this smoke. Do
not grant files, camera, microphone, or contacts unless the exact approved build
cannot reach the test screen without contacts.

Wait until the initial provider sync stops changing the conversation list.
Confirm the three approved thread types remain distinct and participants did
not change. Do not use backup restore or import any file or database.

Read back the role. The only acceptable test-profile holder is
`io.github.only21mil.quik.reactions.debug`. Confirm the owner-profile holder is
unchanged before any test traffic.

## Phase 3: reaction MVP matrix

Run one row at a time. Each base message uses a new opaque marker. A reaction
action happens once unless its row explicitly says remove. Record the chosen
SIM alias, provider thread type, sender alias, target marker alias, operation,
emoji, visible attempt state, final badge state, peer result, and copy count.
For every case that adds and removes a reaction, record a separate copy count
for each operation. For every group-MMS operation, record a separate copy count
for each recipient peer. Do not combine add and remove counts or combine
observations from two devices in one field.

Use `LIKE` for the primary cases. The picker may expose heart, like, dislike,
laugh, emphasis, and question. Stickers and arbitrary emoji are outside this
protocol.

| ID | Thread | Human action | Required result |
| --- | --- | --- | --- |
| SMS-OA | One-to-one SMS | Peer sends a marker. DUT adds `LIKE`, then removes it. | Add and remove each submit once on the same SMS route. DUT shows the badge only after success, then removes it. Peer sees each carrier action at most once. |
| SMS-IA | One-to-one SMS | DUT sends a marker. Peer adds `LIKE`, then removes it. | QUIK attributes the badge to that peer, hides only matched control rows, then removes only that peer's badge. Record the add and remove copy counts separately. |
| MMS-OA | One-to-one text-only MMS | Peer sends a marker. DUT adds `LIKE`, then removes it. | Add and remove each submit once through the provider-proven MMS route. No SMS downgrade, fan-out, or second thread occurs. |
| MMS-IA | One-to-one text-only MMS | DUT sends a marker. Peer adds `LIKE`, then removes it. | QUIK attributes the badge to that peer in the same MMS thread. A removal leaves no badge. Record the add and remove copy counts separately. |
| GM-BASE | Existing true group MMS | DUT sends one marker. | Rachel and Mason each receive one copy in the same group. Record Rachel's and Mason's copy counts separately. No one-to-one thread appears. |
| GM-OA | Existing true group MMS | DUT adds `LIKE`, then removes it from the group marker. | One group-MMS carrier action occurs per operation. Record separate add and remove copy counts on Rachel's and Mason's devices. No per-recipient SMS fan-out occurs. |
| GM-R | Existing true group MMS | Rachel adds `HEART`. | QUIK attaches Rachel's badge to the group marker. Record the add copy count separately on DUT and Mason's device. |
| GM-M | Existing true group MMS | Mason adds `LIKE`. | QUIK shows both badges with the correct sender attribution. Record the add copy count separately on DUT and Rachel's device. |
| GM-RM | Existing true group MMS | Rachel removes `HEART`. | Rachel's badge disappears and Mason's remains. Record the remove copy count separately on DUT and Mason's device. |
| GM-MM | Existing true group MMS | Mason removes `LIKE`. | Mason's badge disappears. No reaction badge remains. Record the remove copy count separately on DUT and Rachel's device. |

A peer client may show a readable raw fallback. QUIK keeps a raw control message
visible if it cannot match syntax, sender, thread, or the unique target. Record
`raw-fallback`; do not delete it. Stop if QUIK attaches a reaction to the wrong
target, hides an unmatched control message, misattributes a sender, removes
another sender's badge, duplicates a carrier action, or changes thread type.

### Dual-SIM cases

Run these only when the approval covers both subscriptions and an approved
one-to-one thread already exists on each route.

| ID | Human action | Required result |
| --- | --- | --- |
| DS-A | On the SIM-A SMS thread, DUT adds and removes `LIKE` once. | Both operations remain on SIM-A and appear once. Record the add and remove copy counts separately. |
| DS-B | On the SIM-B SMS thread, DUT adds and removes `LIKE` once. | Both operations remain on SIM-B and appear once. Record the add and remove copy counts separately. |

Stop if the selected subscription changes, the app routes through the other
SIM, or a callback from one route changes the other route's badge.

## Phase 4: restart and late callback

Run `RL-1` only with separate approval for an app force-stop. Use a new marker
in an approved one-to-one SMS thread. Do not run this case in the group. Swiping
an activity away does not prove that Android restarted the app process.

1. Confirm the original radio state, default SMS SIM, selected thread SIM, and
   current SMS role holder.
2. With the route active, tap `LIKE` once. If QUIK shows a pending state long
   enough, immediately open GrapheneOS App info and tap `Force stop` manually.
3. If the operation reaches a terminal state first, record `RL-1` as `not-run`.
   Do not send another reaction to manufacture a timing window.
4. Relaunch QUIK from its icon. Confirm the pending attempt did not become a
   success badge merely because the process restarted.
5. Wait for the platform callback. Do not use a manual retry or change a radio.
6. Confirm the attempt reaches one terminal result. A successful late callback
   creates one badge and one carrier action. A failed callback creates no badge.
7. Force-stop and relaunch QUIK once more under the same approval. Confirm the
   terminal state and copy count remain unchanged.

Stop if restart unlocks a second reaction tap, a callback binds to the wrong
attempt, a badge appears before success, the carrier action duplicates, or the
route changes.

## Evidence

Copy [grapheneos-device-smoke-evidence.yaml](grapheneos-device-smoke-evidence.yaml)
to a private mode `0700` run directory. Keep the copy mode `0600`. Never fill
the repository template.

Use `unknown` for an observation that has not been made. The template's
`allowed_observation_values` list includes that value. Leave a copy-count field
as `null` until a human observes it, then replace it with a nonnegative integer.

Structured observations are preferred. If a screenshot is essential, request
approval for it and crop it on-device to the synthetic marker and result.
Remove notification previews, phone numbers, avatars, adjacent messages, and
status-bar identifiers before export.

For a blocking failure, a narrow app-process log needs separate approval. Start
capture immediately before one reproduction and stop immediately after it.
Treat the raw log as private message content. Redact bodies, addresses, thread
IDs, subscription IDs, URIs, paths, account identifiers, and notifications.
Delete the raw log after the owner accepts the redacted excerpt.

## Stop conditions

Stop without another send when approval, consent, device, profile, APK digest,
signer, package identity, role holder, participant set, route, SIM, cost, or
radio state differs from the run sheet. Also stop on unexpected history,
duplicate sends, fan-out, wrong-target reactions, wrong attribution, lost
removals, an unsettled sync, an owner-profile change, or private data in
evidence. Do not widen permissions or repeat traffic to troubleshoot.

## Restore and state proof

Restore begins even after a failed case. Every step remains approval-gated.

1. Restore the exact pre-run radio state and default SMS SIM.
2. In the test profile, use the visible Android role prompt to restore the exact
   original SMS holder.
3. Read back the test and owner profile holders. The owner holder must equal its
   preflight value.
4. Open the restored app and confirm it displays the synthetic thread state.
   Do not delete messages unless deletion was approved.
5. If the profile remains, remove only permissions granted for this run and
   uninstall the debug package through Settings.
6. Delete the disposable profile only if the approval explicitly includes that
   destructive action and evidence capture is complete.
7. Read back the final package, role, user-profile, radio, and default-SIM state.

Role and package readbacks are:

```sh
adb shell cmd role get-role-holders --user "$QUIK_TEST_USER_ID" android.app.role.SMS
adb shell pm path --user "$QUIK_TEST_USER_ID" io.github.only21mil.quik.reactions.debug
```

Do not run a global uninstall. Do not clear GrapheneOS Messaging, QUIK, the
telephony provider, or owner-profile data. Rollback is complete only after the
record shows that every touched state equals its approved final value. If role
restoration fails, keep the device online, stop traffic, preserve the profile,
and request recovery approval.
