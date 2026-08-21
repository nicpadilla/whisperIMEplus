# Trusted CI APK signing

The normal `Build and test` workflow treats every pull-request artifact as untrusted. It uses the runner's temporary Android debug key and names the artifact `untrusted-debug-apk-<commit>`. These APKs are suitable for testing, but they do not have a stable trusted identity and cannot update an APK signed by the trusted workflow.

The separate `Trusted APK` workflow runs only after a push reaches `master`. It fails clearly until all four repository secrets below exist:

| Secret | Value |
| --- | --- |
| `CI_SIGNING_KEYSTORE_BASE64` | The complete keystore encoded as one-line Base64 |
| `CI_SIGNING_STORE_PASSWORD` | Keystore password |
| `CI_SIGNING_KEY_ALIAS` | Alias of the signing key inside the keystore |
| `CI_SIGNING_KEY_PASSWORD` | Password for that key |

## Create a new key

Do not reuse `app/debug.keystore` from repository history. That key was public and must be treated as compromised.

Example, run locally with passwords and identity values of your choice:

```bash
keytool -genkeypair -v -keystore whisper-ci-signing.keystore -alias whisper-ci -keyalg RSA -keysize 3072 -validity 10000
base64 -w 0 whisper-ci-signing.keystore > whisper-ci-signing.keystore.b64
```

On macOS, use `base64 < whisper-ci-signing.keystore | tr -d '\n'` for the second command.

Add the Base64 text and passwords under **Repository settings → Secrets and variables → Actions**. Never paste the keystore or passwords into an issue, commit, workflow log, or pull request.

## First trusted build and migration

1. Add all four secrets.
2. Push or merge a reviewed commit to `master`.
3. Confirm that the `Trusted APK` workflow succeeds.
4. Download the `trusted-debug-apk-<commit>` artifact and retain its reported certificate SHA-256 fingerprint.

APKs previously installed from this fork were signed with the old public debug key. Android will not install a new-key APK over an old-key APK with the same package name. Each affected device must uninstall that old build once, then install the first trusted APK. Later trusted APKs will update normally as long as the same secret key remains in use.

## Rotation and recovery

Back up the new keystore and its passwords in a secure location outside GitHub. Losing the key prevents future install-over updates. Rotating it again requires another uninstall/reinstall unless the distribution channel supports Android signing-key rotation.

Pull-request workflows never reference these secrets. The secret-backed workflow exists in a separate `push`-only workflow so unmerged PR code cannot request the trusted signing material.
