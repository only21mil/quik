# Privacy notice for the experimental fork

This notice covers experimental work in `only21mil/quik`. Emoji reaction
sending, receipt, storage, and display are implemented but unreleased. The fork
has no public release at this documentation commit. The preserved upstream
`PRIVACY` file is a separate historical policy for the store listing named
there.

## Message data

QUIK is an Android SMS/MMS client. Its merged debug manifest can request access
to SMS and MMS sending and receipt, existing SMS records, contacts, phone state,
notifications, audio recording, network state, Bluetooth audio, exact alarms,
foreground services, boot events, wake locks, vibration, and legacy external
storage through Android 9. Android version, default-SMS status, and the action a
tester invokes determine which permissions the app requests or uses.

The fork does not request `CALL_PHONE`, `READ_MEDIA_AUDIO`,
`READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`, or vendor launcher-badge permissions.
Phone-number actions open Android's dialer. Attachment selection uses Android's
document picker instead of broad media-library access. The debug package also
declares and uses one package-scoped signature permission so AndroidX can guard
receivers that are not exported.

These permissions support message transport and display, recipient lookup,
attachments, scheduled messages, backup and restore, notification behavior,
audio attachments, widgets, and recovery after reboot. They also expose
sensitive data or capabilities if the app or device is compromised. Review the
exact merged manifest and provenance receipt before installation.

The reaction route asks for `READ_PHONE_STATE` at runtime. Android exposes the
active SIM subscription through that permission. QUIK uses it to match the
provider message to the subscription that will carry the reaction. If the user
denies the permission or revokes it during use, subscription lookup returns no
route and QUIK must not submit the reaction. This permission does not allow QUIK
to place phone calls.

Message bodies, recipients, attachments, contact details, reaction records,
send attempts, and delivery state may be stored in Android's messaging database
or the app's local storage. They may also appear in notifications, device
backups, screenshots, crash output, and debug logs. A debug artifact has no
support promise and uses an ephemeral signing identity.

The fork maintainers do not operate an SMS or MMS delivery service. Your mobile
carrier and the recipient's carrier and messaging client process messages sent
through the app. SMS and MMS are not made end-to-end encrypted by QUIK.

The experimental reaction implementation uses the SMS/MMS message path. A
recipient whose client does not recognize a reaction may receive readable
fallback text as a normal message. That fallback can expose the reaction and
quoted message text to every system that handles the SMS or MMS. Reaction
records and failed or pending reaction sends can remain in local app storage.

## Project services

Public issue reports and repository activity on the GitHub mirror are processed
under GitHub's terms and privacy practices. Repository and project activity
submitted to the authoritative Buzz repository is processed by that service.
The exact public coordinate is listed in [README.md](README.md#repository-authority).

The fork does not ask testers to upload message databases, contact lists, or
unredacted device logs. Do not put private messages, phone numbers, credentials,
device identifiers, or other personal data in public issues. The fork has not
confirmed an enabled GitHub private vulnerability-reporting channel. Follow the
fail-closed instructions in [SECURITY.md](SECURITY.md) and disclose no sensitive
details until that channel is visibly enabled.

## Carrier cost and metadata

Sending or receiving SMS/MMS, including reaction fallbacks, may incur carrier,
roaming, or data charges. Carriers can receive phone numbers, timing, routing
metadata, message content, and attachments according to the transport and the
carrier's own practices. The fork cannot control carrier retention,
interoperability, or billing.

Do not use an experimental build with sensitive conversations unless you have
reviewed the exact source and accept these limits.

A CI debug APK is unsupported evidence, not a release. If someone provides one,
require the exact source commit, GPL-3.0-or-later source and notices,
authenticated run and artifact metadata, original archive, checksum, and a
passing repository-owned provenance receipt. A bare APK cannot establish its
source or signing identity.
