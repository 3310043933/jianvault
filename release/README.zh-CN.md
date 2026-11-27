# 发布 JianVault / 简验证

[English](README.md) | **简体中文**

## 最简单：上传已准备好的 APK

已有安装包时，不需要配置 GitHub Actions 或签名 secrets。

1. 在当前源码目录推送准备好的提交和标签：

   ```powershell
   git push origin codex/main
   git push origin v1.6.0
   ```

2. 打开 [New release](https://github.com/3310043933/jianvault/releases/new)。
3. 选择标签 **v1.6.0**，标题填 **JianVault v1.6.0 / 简验证**。
4. 将准备好的 `RELEASE_NOTES.md` 粘贴到描述里。本次保持勾选 **This is a pre-release**。
5. 上传 `jianvault-1.6.0.apk`、`SHA256SUMS.txt` 和 `SIGNING_CERTIFICATE.txt`，检查草稿，准备好后点击 **Publish release**。

本地发布文件夹还提供「发布参数.txt」方便复制各字段，`RELEASE_DATA.json` 是对应的 Release API 请求数据，两者都不包含签名密码。不要上传私有签名文件。

## 你已经安装的测试版

公开版应用 ID 是 `io.github.jianvault.app`，旧版 `cn.local.simpleauth` 会保留，二者独立安装。在旧版导出加密备份，再安装公开版、导入并核对数据；确认无误后才考虑移除旧版。若旧版还没有备份功能，需要先用原签名更新测试版，不能靠卸载旧版来安装公开版。

开发调试包使用 `io.github.jianvault.app.debug`，也与公开版分开。

## 以后生成新的公开安装包

后续更新必须沿用同一发布私钥，它与用户加密保险库备份时输入的 Key 是两回事。修改 `release/version.properties`，提高 `versionCode`，添加对应版本的中英文发布说明，然后运行：

```powershell
./scripts/build-release.ps1 -SigningProperties C:/private/signing.properties -AndroidSdk C:/Android/Sdk -SkipLint
```

脚本会运行测试、构建关闭调试的签名 APK，检查应用 ID、版本和签名身份，并在源码仓库之外准备安装包、哈希、公开证书信息、发布说明和发布字段。缺少签名、使用 debug 证书、签名错误或编译失败时拒绝打包。`-SkipLint` 是当前预览版的明确选项，能够完整运行 Lint 后应移除。

预期的公开证书哈希位于 `release/signing-certificate.sha256`，私钥和密码不进 Git。初始维护者的本机签名资料保存在 `%LOCALAPPDATA%/JianVault/signing`。请将 keystore 和对应密码另做安全备份；丢失后将无法按当前签名正常覆盖更新。不要每个版本重新生成私钥。

## 双击输入版本号发布（Windows）

配置好四个 GitHub Actions Secrets 后，双击源码根目录的 **`发布新版本.cmd`**，输入比当前更高的版本号，例如 `1.6.1`；直接回车使用建议的下一个补丁版本。需要已安装 Git for Windows，并具有 origin 仓库的推送权限。Git 若提示登录，按提示完成登录即可；无需安装 GitHub CLI、PowerShell 7、JDK 或 Android SDK。

脚本自动提高 `versionCode`，在缺少该版本发布说明时根据提交记录生成中英文说明，将当前所有未忽略的源码修改一起提交，再将当前分支和新版本标签一次推送到 origin。请确保当前未忽略的文件都是要公开的源码；已有该版本的发布说明会保留。脚本会保留 `prerelease` 设置，不会自动把预览版改成正式版。

之后 GitHub Actions 自动构建并公开发布。脚本会打开 Actions 页面；本机显示上传成功只表示推送完成，云端构建和签名验证全部成功后才有可下载的 Release。远程版本已存在、版本号未提高或远程分支存在冲突时会停止，不会强制覆盖。若最后推送失败，本地提交和标签会保留，并显示可重试的推送命令。

也可以从终端指定版本：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\publish-release.ps1 -Version 1.6.1
```

## GitHub Actions 自动打包发布

首次将四个签名 secrets 配置到仓库的 **Settings → Secrets and variables → Actions**：`JIANVAULT_KEYSTORE_BASE64`、`JIANVAULT_STORE_PASSWORD`、`JIANVAULT_KEY_ALIAS`、`JIANVAULT_KEY_PASSWORD`。可以手动填写，也可以运行 `scripts/configure-github-signing.ps1`。

工作流支持两种入口：

- **自动发版**：推送 `v*` 版本标签后，自动检查、打包、验证签名、上传 APK 和校验文件，并公开发布 Release。
- **手动补发**：在 **Actions → Signed release → Run workflow** 选择包含新工作流的 `codex/main` 分支，输入已有标签，例如 `v1.6.0`，也会自动打包并公开发布。已有标签不会因推送新代码而重新触发。

首次发布 `v1.6.0`：先执行 `git push origin codex/main`，再执行 `git push origin v1.6.0`。因为这个标签创建于自动发版配置之前，请使用上述手动入口运行一次。不要移动或强制覆盖现有标签。

以后发布新版本，例如 `v1.6.1`：

1. 修改 `release/version.properties`：设置 `versionName=1.6.1` 并提高 `versionCode`。当前保持 `prerelease=true`，直到完成预览验证。
2. 添加对应的中英文发布说明 `release/notes/v1.6.1.md`，完成源码修改后提交。
3. 在包含新工作流的提交上创建并推送标签：

   ```powershell
   git push origin codex/main
   git tag -a v1.6.1 -m "JianVault 1.6.1"
   git push origin v1.6.1
   ```

标签必须与版本文件一致。只有推送版本标签才会自动发布；普通源码提交只触发 Source checks。任务全部成功后，用户即可在 Releases 的 Assets 下载 APK，不需要你再手动点击 Publish。工作流先创建带附件的草稿，再自动公开；失败时不会覆盖已有 Release。已存在同名 Release 时任务会停止，请先检查现有版本。

若组织或仓库限制了 Actions，请在 **Settings → Actions → General** 允许本工作流使用的 Actions 和 `contents: write` 权限。流程仍跳过 Android Lint；公开版本会根据 `prerelease=true` 标记为预发布。

Source checks 工作流不需要签名 secrets，其中的开发调试包也不是公开发布附件。私钥和密码不能作为 Release 附件或源码上传。

参考：[GitHub Release 文档](https://docs.github.com/en/repositories/releasing-projects-on-github/managing-releases-in-a-repository)、[GitHub Actions secrets](https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-secrets)。
