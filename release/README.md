# Publishing a JianVault release

**English** | [简体中文](README.zh-CN.md)

## Publish the prepared APK manually

You do not need GitHub Actions or signing secrets to upload an already built APK.

1. Push the prepared source commit and tag from this checkout:

   ```powershell
   git push origin codex/main
   git push origin v1.6.0
   ```

2. Open [New release](https://github.com/3310043933/jianvault/releases/new).
3. Select tag **v1.6.0**. Title: **JianVault v1.6.0 / 简验证**.
4. Paste the prepared `RELEASE_NOTES.md` into the description. Keep **This is a pre-release** checked for this preview.
5. Upload `jianvault-1.6.0.apk`, `SHA256SUMS.txt`, and `SIGNING_CERTIFICATE.txt`. Review the draft and click **Publish release** when ready.

The prepared local folder also includes `发布参数.txt` with all field values and `RELEASE_DATA.json` with the corresponding release API body. Those files contain no private signing values. Do not upload private signing material.

## Existing test installations

The public app ID is `io.github.jianvault.app`. The previous app ID `cn.local.simpleauth` remains separate. Export an encrypted backup from the old app, install the public app alongside it, import, and verify the records before removing the old app. A test build older than the backup feature must first be updated with its original matching signer; never uninstall it just to install the public APK.

Developer debug builds use `io.github.jianvault.app.debug`, which also remains separate from public installations.

## Build future releases

Keep the same private release key for future updates. It is not a vault backup key. Update `release/version.properties` with a higher `versionCode`, add matching bilingual release notes, then run:

```powershell
./scripts/build-release.ps1 -SigningProperties C:/private/signing.properties -AndroidSdk C:/Android/Sdk -SkipLint
```

The script runs tests, builds a non-debuggable signed APK, verifies its application ID/version/signing certificate, and prepares the APK, checksums, public certificate information, notes, and publish fields outside the source checkout. The script refuses a debug certificate, the wrong release signer, missing signing values, and failed builds. `-SkipLint` is explicit for the current preview; omit it once full Lint verification is available.

The expected public signing-certificate digest is committed in `release/signing-certificate.sha256`. Private keystores and passwords are never committed. The initial maintainer's local material was generated in `%LOCALAPPDATA%/JianVault/signing`; keep an independent secure backup of both the keystore and its passwords. Losing them prevents normal updates signed with this identity. Do not generate a fresh key for each version.

## Optional: have GitHub Actions create a draft

This is optional and can be configured later. The workflow never publishes automatically.

1. Install GitHub CLI and authenticate to your repository.
2. Set the four signing secrets without printing them:

   ```powershell
   ./scripts/configure-github-signing.ps1 -SigningProperties C:/private/signing.properties
   ```

   Secret names: `JIANVAULT_KEYSTORE_BASE64`, `JIANVAULT_STORE_PASSWORD`, `JIANVAULT_KEY_ALIAS`, `JIANVAULT_KEY_PASSWORD`.

3. Push the source commit and matching tag. In **Actions → Signed release draft → Run workflow**, supply the tag.
4. The workflow builds and verifies the APK, attaches assets to a **draft prerelease**, and stops. Open the draft, review it, and publish manually.

The source-check workflow does not need signing secrets. Its developer APK is not the public release asset. Do not upload a private keystore or passwords as release attachments or source files.

References: [GitHub releases](https://docs.github.com/en/repositories/releasing-projects-on-github/managing-releases-in-a-repository), [GitHub Actions secrets](https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-secrets).
