# JianVault · 简验证

**English** | [简体中文](README.zh-CN.md)

An offline Android vault for TOTP codes, website credentials, recovery codes, and encrypted backups.

Repository: **[3310043933/jianvault](https://github.com/3310043933/jianvault)**

[Download installable APKs](https://github.com/3310043933/jianvault/releases) · [Publishing guide](release/README.md)

Maintainer publishing: after configuring signing secrets, double-click `发布新版本.cmd` and enter a new version to commit source changes and trigger automatic GitHub build and publication. See the [publishing guide](release/README.md).

## Features

- **Authenticator:** scan a QR code or enter a TOTP setup key. Supports SHA1/SHA256/SHA512, 6/8 digits, and refresh periods of 1–300 seconds.
- **Website credentials:** save names, URLs, usernames, passwords, and multiline notes. Organize entries into groups or leave them ungrouped, search by name/URL/username, and copy individual fields or the complete entry.
- **Recovery codes:** store and mark codes used within authenticator entries. Copying a code does not mark it used or change its status on the website. Website credential pages use notes instead of recovery-code controls.
- **Encrypted backups:** supply your own key, export all vault records and groups as encrypted text, copy the text, or save a UTF-8 TXT file. Import through a TXT file or pasted text.
- **Exact-match merge:** skip only records whose complete stored contents match. Any changed field creates a separate entry. Existing records remain intact, including existing local duplicates. Replacement is a separate, confirmed action.
- **21 languages:** choose a language on first launch and change it under **Navigation menu → Language**. Options include representative flags and native names. Follow-system mode is available; saved names, credentials, notes, groups, and backups are never translated.

The navigation menu contains five pages: **Authenticator**, **Website credentials**, **Encrypted import / export**, **Language**, and **Help**. Website group controls stay on the website credentials page.

## Getting started

1. Install a locally built APK on Android 8.0 or later. Set a device screen lock and choose a language on first launch before opening the vault.
2. For TOTP, add the website's authenticator setup key or scan its QR code, then enter the generated code on that website to complete setup. A TOTP setup key is different from a login password. The code changes with time; the setup key does not.
3. For fixed passwords, open **Website credentials** and save an entry. Use the group selector and **Manage groups** on that page. Deleting a group moves its entries to **Ungrouped** without deleting their data.
4. Open entry details to view, edit, move, or copy credentials. Passwords are hidden by default. **Copy all** includes the name, URL, username, password, and nonempty notes.
5. Change the language later from the navigation menu. Switching recreates the interface and requires system authentication again.

## Backup and import

Open **Encrypted import / export**:

- **Export:** enter and confirm your own key of 12–1024 characters. Case, Unicode, and spaces are preserved. Export includes TOTP accounts, website credentials, notes, groups (including empty groups), recovery codes, and used flags. The key is not saved in the backup or persisted by the app.
- **Import:** select the encrypted TXT file or paste the encrypted text, then enter the same key. Nothing changes before decryption and confirmation.
- **Merge:** preview additions and skips. Complete equality includes type, names, URL, username, password, notes, group membership, TOTP key/settings, and recovery-code contents, order, and used flags. Same-name groups are matched before comparing entries; internal group IDs alone do not create duplicates. Group conflicts are preserved separately. Reimporting the same backup is idempotent.
- **Replace:** a separate confirmation replaces the entire local vault. Local records absent from the backup are deleted. If the local vault cannot be read, merging is unavailable and replacement is the recovery option.

Keep the backup and its key separately. **A lost key cannot decrypt a backup.** Import re-encrypts the data with the destination device's local key, so a backup can be transferred between devices. This is manual transfer, not automatic cloud synchronization.

Backups start with `SIMPLEAUTH-BACKUP-1:` and use PBKDF2-HMAC-SHA256 (600,000 iterations, random 16-byte salt), followed by AES-256-GCM with a random 12-byte nonce and an authenticated header. The serialized plaintext limit is 8 MiB and the encrypted TXT limit is 12 MiB. Clipboard export is limited to 250,000 characters; larger backups must be saved as TXT. UTF-8 BOM, CRLF, and wrapped Base64 are accepted on import.

## Security and current limits

- No network permission, telemetry, ads, or automatic cloud sync.
- Local vault contents are encrypted with AES-256-GCM. The AES key is generated and managed by Android Keystore. The app writes the encrypted snapshot atomically into its private no-backup directory.
- Credential data, groups, notes, and recovery codes are encrypted. The device's non-sensitive interface-language preference is stored separately and is not part of a vault backup.
- System authentication gates entry to the app. **The current Keystore key does not itself require authentication for every use.** Screen-lock gating and key-use authorization are distinct protections.
- Sensitive app screens request screenshot blocking. Credential inputs disable view-state saving and autofill. The app requests that keyboards avoid personalized learning; these requests are not a guarantee about third-party keyboards or a compromised system.
- Viewing data requires plaintext in memory. Copying credentials puts plaintext on the system clipboard. The app attempts to clear unchanged credential clipboard contents after 30 seconds when the system permits it. Background clipboard access may be restricted. Encrypted backup text does not use that 30-second clearing timer.
- Uninstalling, clearing app data, losing the device, or losing its system key can make local data inaccessible. Recovery requires an earlier encrypted backup and its matching key.
- This is a personal-use test project. JVM tests and APK builds have been checked; complete on-device UI, picker, clipboard, authentication, and Keystore testing and an independent security audit are still outstanding. Android Lint has not been completed in the original environment because its dependencies could not be downloaded reliably.

The app language changes interface text and copy-field labels only. User-provided strings and stable stored conflict labels are never translated, so existing backups and merge behavior remain compatible across languages.

## Build

Requirements: **JDK 17**, **Android SDK 36**, and the bundled **Gradle 8.13 wrapper**. Minimum Android API: **26**. Configure Android Studio's SDK or create an ignored `local.properties`:

```properties
sdk.dir=C:/Android/Sdk
```

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:lintDebug
```

On macOS/Linux, use `./gradlew` instead. Debug output: `app/build/outputs/apk/debug/app-debug.apk`.

Public releases use application ID `io.github.jianvault.app` and require the fixed private release signer. The old `cn.local.simpleauth` test app is retained for safe migration through encrypted backups. Developer builds use the separate `.debug` application ID. **Do not uninstall an existing vault before exporting and verifying its backup.** See the [release guide](release/README.md) for signed builds and GitHub publishing. Private signing keys, passwords, APKs, build outputs, caches, and `local.properties` are not committed.

## Tests and development

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-17'
.\tests\run.ps1
```

Tests use synthetic credentials only. The suite covers RFC 6238 vectors, recovery codes, exact credential preservation, notes, authenticated encryption, legacy data migration, group handling, encrypted portable backups, complete-record merging, copy labels, and language-resource consistency. JVM tests do not replace Android device testing.

Development instructions: [English](AGENTS.md) · [简体中文](AGENTS.zh-CN.md). Complete necessary verification and create a local Git commit for each finished change.

## Licensing and dependencies

No project-wide license has been selected yet. Third-party component notices and their original licenses are preserved separately: [English](THIRD_PARTY.md) · [简体中文](THIRD_PARTY.zh-CN.md).

## Interface languages

Supported languages: English, Simplified Chinese, Traditional Chinese, Spanish, French, German, Portuguese, Russian, Arabic, Hindi, Bengali, Japanese, Korean, Indonesian, Turkish, Vietnamese, Italian, Thai, Persian, Urdu, and Swahili, plus system default. Arabic, Persian, and Urdu use right-to-left layout and drawer gestures. Flags are representative markers; languages are used across national borders.

English and Simplified Chinese use the original copy. Other locales are machine-assisted initial translations and have not all received native-speaker review. UI text, validation errors, and copied field labels are bundled statically in the APK. The app never translates over the network or translates user data. Existing installations without an explicit language preference see the chooser once; an explicitly saved preference is preserved.
