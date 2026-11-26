param([string]$OutputDirectory)
$ErrorActionPreference = 'Stop'
$projectDir = Split-Path -Parent $PSScriptRoot
$testOutputDir = if ($OutputDirectory) { $OutputDirectory } else { Join-Path $projectDir 'build\core-tests' }
if (-not $env:JAVA_HOME) { throw 'Please set JAVA_HOME to JDK 17.' }
New-Item -ItemType Directory -Path $testOutputDir -Force | Out-Null
$sourceDir = Join-Path $projectDir 'app\src\main\java\cn\local\simpleauth'
$jsonLibrary = Join-Path $PSScriptRoot 'libs\json-20240303.jar'
$testClassPath = $testOutputDir + [IO.Path]::PathSeparator + $jsonLibrary
$testSources = @('Otp.java', 'RecoveryCodes.java', 'LoginDetails.java', 'VaultCipher.java', 'PasswordGroup.java', 'VaultState.java', 'VaultCodec.java', 'PasswordPresentation.java', 'BackupCodec.java', 'VaultMerge.java', 'ErrorMessages.java') | ForEach-Object { Join-Path $sourceDir $_ }
$testSources += @('OtpTest.java', 'RecoveryCodesTest.java', 'LoginDetailsTest.java', 'VaultCipherTest.java', 'PasswordGroupsTest.java', 'NotesTest.java', 'BackupCodecTest.java', 'VaultMergeTest.java', 'InternationalizationTest.java') | ForEach-Object { Join-Path $PSScriptRoot $_ }
& (Join-Path $env:JAVA_HOME 'bin\javac.exe') -encoding UTF-8 -cp $jsonLibrary -d $testOutputDir @testSources
if ($LASTEXITCODE -ne 0) { throw 'Test compilation failed.' }
& (Join-Path $env:JAVA_HOME 'bin\java.exe') -cp $testOutputDir cn.local.simpleauth.OtpTest
if ($LASTEXITCODE -ne 0) { throw 'OTP tests failed.' }
& (Join-Path $env:JAVA_HOME 'bin\java.exe') -cp $testOutputDir cn.local.simpleauth.RecoveryCodesTest
if ($LASTEXITCODE -ne 0) { throw 'Recovery-code tests failed.' }
& (Join-Path $env:JAVA_HOME 'bin\java.exe') -cp $testOutputDir cn.local.simpleauth.LoginDetailsTest
if ($LASTEXITCODE -ne 0) { throw 'Login-model tests failed.' }
& (Join-Path $env:JAVA_HOME 'bin\java.exe') -cp $testOutputDir cn.local.simpleauth.VaultCipherTest
if ($LASTEXITCODE -ne 0) { throw 'Encryption-envelope tests failed.' }
& (Join-Path $env:JAVA_HOME 'bin\java.exe') -cp $testClassPath cn.local.simpleauth.PasswordGroupsTest
if ($LASTEXITCODE -ne 0) { throw 'Grouping and migration tests failed.' }
& (Join-Path $env:JAVA_HOME 'bin\java.exe') -cp $testClassPath cn.local.simpleauth.NotesTest
if ($LASTEXITCODE -ne 0) { throw 'Notes tests failed.' }
& (Join-Path $env:JAVA_HOME 'bin\java.exe') -cp $testClassPath cn.local.simpleauth.BackupCodecTest
if ($LASTEXITCODE -ne 0) { throw 'Encrypted-backup tests failed.' }
& (Join-Path $env:JAVA_HOME 'bin\java.exe') -cp $testClassPath cn.local.simpleauth.VaultMergeTest
if ($LASTEXITCODE -ne 0) { throw 'Exact-match merge tests failed.' }

& (Join-Path $env:JAVA_HOME 'bin\java.exe') -cp $testClassPath cn.local.simpleauth.InternationalizationTest
if ($LASTEXITCODE -ne 0) { throw 'Internationalization tests failed.' }
& (Join-Path $PSScriptRoot 'verify-localization.ps1') -ProjectDirectory $projectDir
