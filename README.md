![QUIK](.github/octoshrimpy_quik.jpg)

# QUIK experimental fork

This is the `only21mil/quik` experimental fork of
[quik-sms/quik](https://github.com/quik-sms/quik), based on upstream commit
`555b8822c654b8ee85bb9d3f961eb30f232b1079`. QUIK is an open-source Android
SMS and MMS app and a continuation of
[QKSMS](https://github.com/moezbhatti/qksms).

This fork implements experimental emoji reaction sending, receipt, storage, and
display through the existing SMS/MMS message flow. The reaction code is
unreleased and unsupported. It has not completed release qualification, and
this fork has no public release.

The `Android main` workflow may retain a short-lived debug APK as CI evidence.
That artifact is not a release, a supported build, or an installation
recommendation. It uses an ephemeral debug signing key and may disappear when
the CI retention period ends. Do not redistribute it as an official QUIK build.
The PR and main workflows compile Android instrumentation tests without
installing or running them on a device.

Release and F-Droid packaging require an explicitly approved, owner-only
PKCS12 keystore outside the repository. The build fails closed when approval,
path, alias, or password inputs are absent. See
[release signing](docs/release-signing.md) for the configuration contract.

RCS is outside this fork's scope. The fork does not implement, test, or claim
RCS messaging or RCS reactions.

## Repository authority

Buzz is the source of truth for this fork. The completed bootstrap readback
matched every Buzz and GitHub branch and tag before the repository entered
normal mirror service.

- Buzz repository coordinate:
  `30617:4a34c131ec5cb5dd9a200bac619bbd103c0793e068fad278d1de59203d05b97d:quik`
- Buzz Git URL:
  [quik](https://framework-desktop.tail69757d.ts.net:38443/git/4a34c131ec5cb5dd9a200bac619bbd103c0793e068fad278d1de59203d05b97d/quik)
- GitHub CI mirror: [only21mil/quik](https://github.com/only21mil/quik)

Submit reviewed source changes to Buzz first. The installed mirror copies Buzz
branches and tags to GitHub for public access and CI. A GitHub-only ref can be
overwritten or removed on the next mirror run.

## SMS/MMS limits

- A reaction sent through SMS/MMS is carrier traffic. It can count as a text or
  multimedia message and may incur carrier, roaming, or data charges.
- Other messaging clients may not recognize reaction metadata. They may show a
  readable fallback as an ordinary message, attach it to the wrong message, or
  render it differently.
- Carrier transformations, multipart SMS, MMS conversion, group messaging, and
  message ordering can affect interoperability. Compatibility must be tested on
  the actual devices, clients, and carriers involved.
- SMS and MMS are not made end-to-end encrypted by this app. Carriers,
  recipients, Android's messaging database, notifications, screenshots, and
  debug logs may handle message content and metadata.

Read the [fork privacy notice](PRIVACY-FORK.md) before testing with real
messages. The upstream `PRIVACY` file is preserved as an upstream historical
document and applies to the store listing named in that file, not to an
unreleased fork build.

The [GrapheneOS device smoke protocol](docs/grapheneos-device-smoke.md) covers
the reaction MVP. It is an inert runbook. It does not authorize installation,
SMS or MMS traffic, a role change, radio changes, or device cleanup.

## Exact-source debug delivery

Do not deliver a development APK by filename or branch name alone. A recipient
must receive all of the following as one evidence set:

- the full 40-character source commit from `only21mil/quik`;
- the authenticated GitHub Actions run and artifact metadata for that exact
  commit and the `Android main` workflow;
- the original artifact ZIP, its GitHub-reported SHA-256 digest, the embedded
  `QUIK-debug.apk`, and the embedded checksum file;
- a passing receipt from `scripts/verify-debug-apk-provenance.sh`; and
- the authenticated corresponding-source archive for that commit, its SHA-256
  digest, the immutable commit and license links, and the complete
  GPL-3.0-or-later source,
  build scripts, notices, and fork modifications.

The verifier rejects a substituted repository, workflow, run, commit, artifact,
archive, APK, checksum, corresponding source, license, policy, schema,
permission set, or exported Android entry point. It acquires GitHub metadata and
both archives itself through the authenticated `gh` session for `github.com`.
It does not accept caller-supplied API responses or archives as authority.
The merged-manifest authority gate separately checks the exact permission set,
package queries, and default-SMS-role components in debug, release, and F-Droid
variants. Release and F-Droid automation remain disabled.

GPL-3.0-or-later obligations apply even to an unsupported debug copy. Providing
an APK without the exact corresponding source and license material is not an
accepted delivery path for this fork.

## Support and reports

Use [only21mil/quik issues](https://github.com/only21mil/quik/issues) for fork
bugs and development discussion. Do not include private messages, phone
numbers, credentials, or unredacted logs. Report suspected vulnerabilities as
described in [SECURITY.md](SECURITY.md), not in a public issue.

If a problem also occurs on an unmodified upstream build, report it to the
[upstream QUIK issue tracker](https://github.com/quik-sms/quik/issues). The
upstream project also maintains the
[#quik-sms:matrix.org](https://matrix.to/#/#quik-sms:matrix.org) community room.

## Upstream credit

This fork preserves the upstream history and credit. QUIK was developed and
maintained upstream by [Marcos Jones](https://github.com/octoshrimpy). QUIK
continues QKSMS, created and maintained by
[Moez Bhatti](https://github.com/moezbhatti). The bundled Android SMS/MMS work
also credits [Jake Klinker](https://github.com/klinker41) and
[Luke Klinker](https://github.com/klinker24).

## Contributing and license

See [CONTRIBUTING.md](CONTRIBUTING.md) for the inherited upstream development
guidelines. Fork changes must follow the Buzz-first repository authority above.

QUIK and this modified fork are distributed under the GNU General Public
License version 3. The full terms are in [LICENSE](LICENSE). Upstream copyright
notices and license headers remain in place.
