# Project instructions

**English** | [简体中文](AGENTS.zh-CN.md)

- The canonical source checkout is `D:\p\github\简验证`. Make future changes in this checkout.
- For each completed user-requested change, run the necessary verification and create a local Git commit describing the change.
- Commit only work related to the current request; preserve unrelated user changes.
- Keep source code, build configuration, required dependencies, tests, and documentation in the repository. Do not commit APK/AAB files, build outputs, caches, `local.properties`, private signing keys, or real vault data.
- Run core checks with `tests/run.ps1`, using JDK 17 through `JAVA_HOME`. Android builds require SDK 36. Keep machine-specific configuration ignored.
- Never write passwords, notes, recovery codes, or user-supplied backup keys to logs or plaintext persistence. Use synthetic test data only.
- Keep English and Chinese documentation linked and consistent. UI strings belong in `values/strings.xml` and `values-zh/strings.xml`; translate validation errors at the presentation boundary only.
- Do not translate saved user content or change the backup schema, encryption, stable merge labels, or record equality merely to change the UI language.
