# Third-party components

**English** | [简体中文](THIRD_PARTY.zh-CN.md)

- ZXing Android Embedded 4.3.0: https://github.com/journeyapps/zxing-android-embedded — Apache License 2.0.
- ZXing Core 3.4.1: https://github.com/zxing/zxing — Apache License 2.0.
- AndroidX Core 1.18.0 and its transitive dependencies: https://android.googlesource.com/platform/frameworks/support/ — Apache License 2.0.
- Kotlin Standard Library 2.2.20: https://github.com/JetBrains/kotlin — Apache License 2.0.
- Gradle Wrapper 8.13: https://github.com/gradle/gradle — Apache License 2.0.
- JSON-java 20240303 (JVM tests only, not included in APK): https://github.com/stleary/JSON-java — Public Domain; license included in licenses/JSON-java.txt. Official binary under tests/libs.

License text: https://www.apache.org/licenses/LICENSE-2.0

No third-party source code is copied into the app source. Official Maven binary artifacts are included under app/libs to avoid a local Gradle TLS issue. License text is included under licenses/Apache-2.0.txt; upstream notices remain in the original artifacts. The Gradle wrapper binary is included for reproducible builds.
