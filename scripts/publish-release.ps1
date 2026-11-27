param([string]$Version)
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot

function Invoke-ReleaseGit {
    param([string[]]$GitArguments, [switch]$Capture)
    # Windows PowerShell 5.1 treats redirected native stderr as error records.
    $ErrorActionPreference = 'Continue'
    $output = @(& git -C $repo @GitArguments 2>&1)
    $exitCode = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
    if ($exitCode -ne 0) {
        throw (("git " + ($GitArguments -join ' ') + " failed.`n") + ($output -join "`n"))
    }
    if ($Capture) { return @($output | ForEach-Object { $_.ToString() }) }
    foreach ($line in $output) { Write-Host $line }
}

try {
    if (-not (Get-Command git -ErrorAction SilentlyContinue)) { throw '请先安装 Git for Windows / Install Git for Windows first.' }
    $branch = (Invoke-ReleaseGit -GitArguments @('symbolic-ref','--quiet','--short','HEAD') -Capture) -join ''
    if (-not $branch) { throw '请先切换到源码分支 / Check out a source branch first.' }
    $remoteUrl = (Invoke-ReleaseGit -GitArguments @('remote','get-url','origin') -Capture) -join ''
    if (-not $remoteUrl) { throw '未配置 origin / Configure the origin remote first.' }
    if (-not (Test-Path -LiteralPath (Join-Path $repo '.github/workflows/release.yml'))) { throw '缺少发布工作流 / Release workflow missing.' }

    $versionPath = Join-Path $repo 'release/version.properties'
    $properties = [IO.File]::ReadAllText($versionPath)
    $currentMatch = [regex]::Match($properties,'(?m)^versionName=(\d+\.\d+\.\d+)\r?$')
    $codeMatch = [regex]::Match($properties,'(?m)^versionCode=(\d+)\r?$')
    if (-not $currentMatch.Success -or -not $codeMatch.Success) { throw '无法读取版本配置 / Invalid version metadata.' }
    $current = $currentMatch.Groups[1].Value
    $currentVersion = [version]$current
    $suggested = '{0}.{1}.{2}' -f $currentVersion.Major,$currentVersion.Minor,($currentVersion.Build+1)
    Write-Host "当前版本 / Current: $current"
    Write-Host "上传分支 / Branch: $branch"
    Write-Host "目标仓库 / Remote: $remoteUrl"
    if (-not $Version) {
        $Version = Read-Host "输入新版本号，直接回车使用 $suggested / New version"
        if (-not $Version) { $Version = $suggested }
    }
    $Version = $Version.Trim() -replace '^v',''
    if ($Version -notmatch '^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$') { throw '版本号格式应为 1.6.1 / Use a version such as 1.6.1.' }
    if ([version]$Version -le $currentVersion) { throw "新版本必须高于 $current / The new version must be higher than $current." }
    $newCode = [long]$codeMatch.Groups[1].Value + 1
    if ($newCode -gt 2100000000) { throw 'Android versionCode limit reached.' }
    $tag = 'v'+$Version
    if (@(Invoke-ReleaseGit -GitArguments @('tag','--list',$tag) -Capture).Count) { throw "本地标签 $tag 已存在，请使用新版本号 / Local tag already exists." }
    if (@(Invoke-ReleaseGit -GitArguments @('diff','--name-only','--diff-filter=U') -Capture).Count) { throw '请先解决合并冲突 / Resolve merge conflicts first.' }

    $changedFiles = @(
        Invoke-ReleaseGit -GitArguments @('diff','--name-only','HEAD') -Capture
        Invoke-ReleaseGit -GitArguments @('ls-files','--others','--exclude-standard') -Capture
    ) | Sort-Object -Unique
    foreach ($file in $changedFiles) {
        if ($file -match '(?i)\.(jks|keystore|p12|pfx|apk|aab)$|(^|/)(\.env(\..*)?|local\.properties|.*signing.*\.properties|.*(secret|credential).*\.(txt|json|ya?ml)|vault\.bin)$|(^|/)(build|\.gradle|\.secrets)/') {
            throw "请先移除待提交的私密文件或构建产物 / Remove private or generated files from pending changes: $file"
        }
    }
    Write-Host '检查远程权限与版本标签 / Checking remote access and release tag...'
    if (@(Invoke-ReleaseGit -GitArguments @('ls-remote','--exit-code','origin') -Capture).Count -eq 0) { throw '无法读取远程仓库 / Remote repository unavailable.' }
    if (@(Invoke-ReleaseGit -GitArguments @('ls-remote','--tags','origin',"refs/tags/$tag") -Capture).Count) { throw "远程标签 $tag 已存在 / Remote tag already exists." }
    Invoke-ReleaseGit -GitArguments @('push','--dry-run','origin',"HEAD:refs/heads/$branch")

    $notesPath = Join-Path $repo ('release/notes/'+$tag+'.md')
    $utf8 = [Text.UTF8Encoding]::new($false)
    if (-not (Test-Path -LiteralPath $notesPath)) {
        $previousTag = 'v'+$current
        $hasPreviousTag = @(Invoke-ReleaseGit -GitArguments @('tag','--list',$previousTag) -Capture).Count -gt 0
        $logArguments = @('log','--format=%s','--no-merges','-20')
        if ($hasPreviousTag) { $logArguments += ($previousTag+'..HEAD') }
        $subjects = @(Invoke-ReleaseGit -GitArguments $logArguments -Capture)
        $updates = if ($subjects.Count) { ($subjects | ForEach-Object { '- '+$_ }) -join "`n" } else { '- Source updates / 源码更新' }
        if ($changedFiles.Count) { $updates += "`n- Includes current source changes / 包含当前源码修改" }
        $preview = $properties -match '(?m)^prerelease=true\r?$'
        $previewEnglish = if ($preview) { 'Public preview; full device validation and independent security auditing remain incomplete. Android Lint is currently skipped.' } else { 'See the changes below.' }
        $previewChinese = if ($preview) { '公开预览版；完整真机验证和独立安全审计尚未完成，目前仍跳过 Android Lint。' } else { '更新内容见下方。' }
        $notes = @"
# JianVault $tag · 简验证

[English](#english) | [简体中文](#简体中文)

## English

Download **jianvault-$Version.apk** from Assets. Requires Android 8.0 or later.
Update the existing public app in place; keep an encrypted backup before updating.

$previewEnglish

### Changes

$updates

## 简体中文

在 Assets 中下载 **jianvault-$Version.apk** 即可安装，支持 Android 8.0 及以上。
公开版可直接覆盖更新；更新前请保留加密备份。

$previewChinese

### 更新内容

$updates
"@
        [IO.File]::WriteAllText($notesPath,$notes+"`n",$utf8)
    }
    $properties = [regex]::Replace($properties,'(?m)^versionName=[^\r\n]+',('versionName='+$Version))
    $properties = [regex]::Replace($properties,'(?m)^versionCode=[^\r\n]+',('versionCode='+$newCode))
    [IO.File]::WriteAllText($versionPath,$properties,$utf8)
    Write-Host '提交当前源码及版本信息 / Committing current source and version metadata...'
    Invoke-ReleaseGit -GitArguments @('add','--all')
    Invoke-ReleaseGit -GitArguments @('diff','--cached','--check')
    Invoke-ReleaseGit -GitArguments @('commit','-m',('Release '+$tag))
    Invoke-ReleaseGit -GitArguments @('tag','-a',$tag,'-m',('JianVault '+$Version))
    Write-Host '推送源码和版本标签 / Pushing source and release tag...'
    try {
        Invoke-ReleaseGit -GitArguments @('push','--atomic','origin',("HEAD:refs/heads/"+$branch),("refs/tags/"+$tag))
    } catch {
        Write-Host "本地提交及标签已保留。排除网络或权限问题后可执行 / Commit and tag kept; retry with:"
        Write-Host ('git push --atomic origin "HEAD:refs/heads/'+$branch+'" "refs/tags/'+$tag+'"')
        throw
    }
    Write-Host "已上传 $tag，GitHub 正在自动打包发布 / Uploaded; GitHub will build and publish."
    Write-Host '推送成功不代表云端构建已完成，请在 Actions 确认 / Check Actions for the build result.'
    $repositorySlug = [regex]::Match($remoteUrl,'github\.com[:/]([^/]+/[^/]+?)(?:\.git)?/?$')
    if ($repositorySlug.Success) {
        $baseUrl = 'https://github.com/'+$repositorySlug.Groups[1].Value
        Write-Host ($baseUrl+'/actions')
        Write-Host ($baseUrl+'/releases/tag/'+$tag)
        if ($env:OS -eq 'Windows_NT' -and -not $env:JIANVAULT_NO_BROWSER) {
            try { Start-Process ($baseUrl+'/actions') | Out-Null } catch { Write-Host '请手动打开上方链接 / Open the link above.' }
        }
    }
    exit 0
} catch {
    Write-Host ("发布未完成 / Release not completed: "+$_.Exception.Message) -ForegroundColor Red
    Write-Host '不会强制覆盖远程代码或已有版本。请修复上述问题后重试 / No remote history or existing release was force-overwritten.'
    exit 1
}
