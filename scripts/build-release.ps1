param(
    [string]$SigningProperties = $env:JIANVAULT_SIGNING_PROPERTIES,
    [string]$AndroidSdk = $env:ANDROID_HOME,
    [string]$OutputDirectory,
    [switch]$SkipLint,
    [switch]$SkipTests,
    [string[]]$GradleArguments = @()
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
function Read-Properties([string]$path) {
    $result = @{}
    foreach ($line in [IO.File]::ReadAllLines($path)) {
        if ($line -match '^\s*(#|$)') { continue }
        $parts = $line.Split('=',2); if ($parts.Count -eq 2) { $result[$parts[0].Trim()] = $parts[1].Trim() }
    }
    return $result
}
if (-not $env:JAVA_HOME) { throw 'Set JAVA_HOME to JDK 17.' }
if (-not $AndroidSdk) { $AndroidSdk = $env:ANDROID_SDK_ROOT }
if (-not $AndroidSdk -or -not (Test-Path -LiteralPath $AndroidSdk)) { throw 'Set ANDROID_HOME or pass -AndroidSdk.' }
$env:ANDROID_HOME = $AndroidSdk
$version = Read-Properties (Join-Path $repo 'release/version.properties')
$name = $version['versionName']; $tag = 'v'+$name
if ($name -notmatch '^\d+\.\d+\.\d+(-[A-Za-z0-9.-]+)?$') { throw 'Invalid release version.' }
if (-not $OutputDirectory) { $OutputDirectory = Join-Path (Split-Path -Parent $repo) ('jianvault-release/'+$tag) }
$OutputDirectory = [IO.Path]::GetFullPath($OutputDirectory)
if ($OutputDirectory.Equals([IO.Path]::GetFullPath($repo),[StringComparison]::OrdinalIgnoreCase) -or $OutputDirectory.StartsWith([IO.Path]::GetFullPath($repo)+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) { throw 'Release artifacts must be outside the source repository.' }
if (-not $SkipTests) { & (Join-Path $repo 'tests/run.ps1') -OutputDirectory (Join-Path ([IO.Path]::GetTempPath()) 'jianvault-release-tests') }
$arguments = @('-p',$repo,'--console=plain',':app:assembleRelease') + $GradleArguments
if ($SigningProperties) {
    if (-not (Test-Path -LiteralPath $SigningProperties)) { throw 'Signing properties file not found.' }
    $arguments += '-PjianvaultSigningProperties='+[IO.Path]::GetFullPath($SigningProperties)
}
if ($SkipLint) { $arguments += @('-x','lintVitalAnalyzeRelease','-x','lintVitalReportRelease','-x','lintVitalRelease') }
$wrapper = Join-Path $repo $(if ($IsWindows) { 'gradlew.bat' } else { 'gradlew' })
& $wrapper @arguments
if ($LASTEXITCODE -ne 0) { throw 'Release build failed. No assets were packaged.' }
$apk = Join-Path $repo 'app/build/outputs/apk/release/app-release.apk'
if ($env:JIANVAULT_BUILT_APK) { $apk = $env:JIANVAULT_BUILT_APK }
if (-not (Test-Path -LiteralPath $apk)) { throw 'Expected release APK not found.' }
$tools = Join-Path $AndroidSdk 'build-tools/36.0.0'
$aapt = Join-Path $tools $(if ($IsWindows) { 'aapt.exe' } else { 'aapt' })
$signer = Join-Path $tools $(if ($IsWindows) { 'apksigner.bat' } else { 'apksigner' })
$inspectionApk = Join-Path ([IO.Path]::GetTempPath()) ("jianvault-inspect-"+[guid]::NewGuid().ToString("N")+".apk")
Copy-Item -LiteralPath $apk -Destination $inspectionApk
$metadata = @(& $aapt dump badging $inspectionApk)
if ($LASTEXITCODE -ne 0) { throw 'APK metadata inspection failed.' }
if (-not ($metadata -match ("package: name='"+[regex]::Escape($version['applicationId'])+"' versionCode='"+$version['versionCode']+"' versionName='"+[regex]::Escape($name)+"'"))) { throw 'APK identity/version differs from release metadata.' }
if (-not ($metadata -match "sdkVersion:'26'")) { throw 'Unexpected minimum Android version.' }
if ($metadata -match 'application-debuggable|android.permission.INTERNET') { throw 'Unexpected debugging or network capability.' }
$signature = @(& $signer verify --verbose --print-certs $inspectionApk)
if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
$digestLine = @($signature | Select-String 'Signer #1 certificate SHA-256 digest: ([a-fA-F0-9]{64})')
if ($digestLine.Count -ne 1) { throw 'Expected exactly one signing certificate.' }
$digest = $digestLine[0].Matches[0].Groups[1].Value.ToLowerInvariant()
$expected = [IO.File]::ReadAllText((Join-Path $repo 'release/signing-certificate.sha256')).Trim().ToLowerInvariant()
if ($digest -ne $expected -or $signature -match 'CN=Android Debug') { throw 'Wrong release signer. Do not publish this APK.' }
Remove-Item -LiteralPath $inspectionApk -Force
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$assetName = 'jianvault-'+$name+'.apk'
Copy-Item -LiteralPath $apk -Destination (Join-Path $OutputDirectory $assetName)
$notesPath = Join-Path $repo ('release/notes/'+$tag+'.md')
if (-not (Test-Path -LiteralPath $notesPath)) { throw 'Release notes missing.' }
Copy-Item -LiteralPath $notesPath -Destination (Join-Path $OutputDirectory 'RELEASE_NOTES.md')
$utf8 = [Text.UTF8Encoding]::new($false)
$certificate = "JianVault release signing certificate`nApplication ID: $($version['applicationId'])`nSHA-256: $digest`n`nAll future public APK updates must use this signing identity.`n"
[IO.File]::WriteAllText((Join-Path $OutputDirectory 'SIGNING_CERTIFICATE.txt'),$certificate,$utf8)
$hash = (Get-FileHash -LiteralPath (Join-Path $OutputDirectory $assetName) -Algorithm SHA256).Hash.ToLowerInvariant()
[IO.File]::WriteAllText((Join-Path $OutputDirectory 'SHA256SUMS.txt'),"$hash  $assetName`n",$utf8)
$commit = (& git -C $repo rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0) { throw 'Cannot determine release commit.' }
$title = 'JianVault '+$tag+' / 简验证'
$pre = $version['prerelease'] -eq 'true'
$data = [ordered]@{ tag_name=$tag; target_commitish=$commit; name=$title; body=[IO.File]::ReadAllText($notesPath); draft=$true; prerelease=$pre; make_latest='false' }
[IO.File]::WriteAllText((Join-Path $OutputDirectory 'RELEASE_DATA.json'),($data | ConvertTo-Json -Depth 5),$utf8)
$guide = "GitHub Release fields / GitHub 发布参数`n`nRepository: https://github.com/3310043933/jianvault`nTag: $tag`nTarget commit: $commit`nTitle: $title`nPre-release / 预发布: $pre`nDescription / 描述: copy RELEASE_NOTES.md`n`nUpload these assets / 上传附件:`n$assetName`nSHA256SUMS.txt`nSIGNING_CERTIFICATE.txt`n`nUsers install the APK, not GitHub's Source code ZIP.`n用户下载安装 APK，而不是 Source code 源码压缩包。`n`nDo not upload signing files or private passwords.`n不要上传签名私钥及密码文件。`n"
[IO.File]::WriteAllText((Join-Path $OutputDirectory '发布参数.txt'),$guide,$utf8)
Write-Output ('Release assets ready: '+$OutputDirectory)
Write-Output ('APK SHA-256: '+$hash)
Write-Output ('Signing certificate SHA-256: '+$digest)
if ($SkipLint) { Write-Output 'Android Lint was explicitly skipped; keep the release marked as a preview.' }
