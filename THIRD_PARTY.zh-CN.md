# 第三方组件

[English](THIRD_PARTY.md) | **简体中文**

- ZXing Android Embedded 4.3.0：[项目](https://github.com/journeyapps/zxing-android-embedded)，Apache License 2.0。
- ZXing Core 3.4.1：[项目](https://github.com/zxing/zxing)，Apache License 2.0。
- AndroidX Core 1.18.0 及其传递依赖：[项目](https://android.googlesource.com/platform/frameworks/support/)，Apache License 2.0。
- Kotlin 标准库 2.2.20：[项目](https://github.com/JetBrains/kotlin)，Apache License 2.0。
- Gradle Wrapper 8.13：[项目](https://github.com/gradle/gradle)，Apache License 2.0。
- JSON-java 20240303：[项目](https://github.com/stleary/JSON-java)，Public Domain，仅用于 JVM 测试，不打包到 APK。原始声明见 `licenses/JSON-java.txt`。

Apache 2.0 原始文本保存在 `licenses/Apache-2.0.txt`，也可查看[官方许可证](https://www.apache.org/licenses/LICENSE-2.0)。原始许可证不作翻译替换，本文件仅提供中文组件说明。

没有将第三方实现源码复制到应用源码中。官方 Maven 扫码二进制依赖位于 `app/libs`，用于绕过原环境中的 Gradle TLS 问题；测试 JSON 运行库位于 `tests/libs`。上游声明保留在原始构件中，Gradle wrapper 二进制随源码提供。
