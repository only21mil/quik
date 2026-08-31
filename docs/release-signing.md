# Release signing

QUIK Reactions does not keep a signing key or signing credentials in this repository. Debug builds use Android's disposable debug key. Release and F-Droid packaging fail before artifact creation unless Victor or Rachel has approved a specific out-of-tree PKCS12 keystore for that run.

The signing operator must keep the keystore outside the repository and deny group and other file access. Do not copy, generate, decode, or import a key in a workflow or repository directory. Keep the password values in `$HOME/.config/sats/secrets.env`, with the directory at mode `0700` and the file at mode `0600`. Load that file without printing it.

The build reads these environment variables:

- `QUIK_RELEASE_KEYSTORE_PATH`: absolute path to the approved PKCS12 file
- `QUIK_RELEASE_KEY_ALIAS`: alias inside that file
- `QUIK_RELEASE_STORE_PASSWORD`: store password
- `QUIK_RELEASE_KEY_PASSWORD`: key password
- `QUIK_RELEASE_SIGNING_APPROVED_BY`: exactly `Victor` or `Rachel`
- `QUIK_RELEASE_SIGNING_APPROVAL_REF`: the approval receipt or thread reference

The path must resolve outside the repository, name a regular file rather than a symbolic link, be owned by the effective signing user, be readable by its owner, and grant no group or other access. The approver value is compared exactly; leading or trailing whitespace is rejected. Gradle does not fall back to a repository path, legacy CI variable names, debug key, or unsigned release artifact.

After approval, load the stored environment values and run the validation gate before any release packaging command:

```bash
set -a
. "$HOME/.config/sats/secrets.env"
set +a
./gradlew --no-daemon :presentation:validateReleaseSigningConfiguration
```

Validation checks configuration and file metadata. It does not inspect the keystore. A later approved release procedure must verify the key identity before signing and record the artifact digest after signing. This repository does not automate either action.

Without all six values, the `assemble`, `bundle`, `package`, and `sign` artifact tasks for both the release and F-Droid variants stop with `Release signing configuration rejected`. Release compilation tasks remain available for unsigned verification because they do not produce an installable release artifact.
