param([string]$ProjectDirectory = (Split-Path -Parent $PSScriptRoot))
$ErrorActionPreference = 'Stop'
$fixture = Join-Path ([IO.Path]::GetTempPath()) ('jianvault-publish-test-'+[guid]::NewGuid().ToString('N'))
$remote = Join-Path $fixture 'remote.git'
$checkout = Join-Path $fixture 'source'
$other = Join-Path $fixture 'other'
$utf8 = [Text.UTF8Encoding]::new($false)
function Test-Git([string]$Directory,[string[]]$Arguments) {
    $ErrorActionPreference = 'Continue'
    $result = @(& git -C $Directory @Arguments 2>&1)
    $code = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
    if ($code -ne 0) { throw ($result -join "`n") }
    return @($result | ForEach-Object { $_.ToString() })
}
function Assert-True([bool]$Condition,[string]$Message) {
    if (-not $Condition) { throw $Message }
}
function Run-Publisher([string]$Version,[int]$ExpectedExit) {
    $ErrorActionPreference = 'Continue'
    $result = @(& $shellExe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $checkout 'scripts/publish-release.ps1') -Version $Version 2>&1)
    $code = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
    if ($code -ne $ExpectedExit) { throw ("Unexpected publisher exit $code for $Version`n"+($result -join "`n")) }
}
$previousBrowser = $env:JIANVAULT_NO_BROWSER
$env:JIANVAULT_NO_BROWSER = '1'
try {
    $shellExe = if ($env:OS -eq 'Windows_NT') { Join-Path $env:SystemRoot 'System32/WindowsPowerShell/v1.0/powershell.exe' } else { (Get-Command pwsh).Source }
    New-Item -ItemType Directory -Path $fixture -Force | Out-Null
    Test-Git $fixture @('init','--bare',$remote) | Out-Null
    Test-Git $fixture @('clone',$remote,$checkout) | Out-Null
    Test-Git $checkout @('config','user.name','Release Integration Test') | Out-Null
    Test-Git $checkout @('config','user.email','release-test@example.invalid') | Out-Null
    Test-Git $checkout @('checkout','-b','codex/test') | Out-Null
    foreach ($dir in @('scripts','release/notes','.github/workflows')) { New-Item -ItemType Directory -Path (Join-Path $checkout $dir) -Force | Out-Null }
    Copy-Item -LiteralPath (Join-Path $ProjectDirectory 'scripts/publish-release.ps1') -Destination (Join-Path $checkout 'scripts/publish-release.ps1')
    Copy-Item -LiteralPath (Join-Path $ProjectDirectory '.github/workflows/release.yml') -Destination (Join-Path $checkout '.github/workflows/release.yml')
    [IO.File]::WriteAllText((Join-Path $checkout 'release/version.properties'),"versionName=1.6.0`nversionCode=11`nprerelease=true`n",$utf8)
    [IO.File]::WriteAllText((Join-Path $checkout 'README.md'),'Synthetic fixture',$utf8)
    Test-Git $checkout @('add','--all') | Out-Null
    Test-Git $checkout @('commit','-m','Initial fixture') | Out-Null
    Test-Git $checkout @('tag','v1.6.0') | Out-Null
    Test-Git $checkout @('push','origin','HEAD:refs/heads/codex/test','refs/tags/v1.6.0') | Out-Null

    [IO.File]::WriteAllText((Join-Path $checkout 'README.md'),'Updated synthetic source',$utf8)
    [IO.File]::WriteAllText((Join-Path $checkout 'Example.java'),'// Synthetic source',$utf8)
    Run-Publisher 'v1.6.1' 0
    $metadata = [IO.File]::ReadAllText((Join-Path $checkout 'release/version.properties'))
    Assert-True ($metadata.Contains('versionName=1.6.1') -and $metadata.Contains('versionCode=12') -and $metadata.Contains('prerelease=true')) 'Incorrect version metadata.'
    $notes = [IO.File]::ReadAllText((Join-Path $checkout 'release/notes/v1.6.1.md'))
    Assert-True ($notes.Contains('## English') -and $notes.Contains('## 简体中文') -and $notes.Contains('jianvault-1.6.1.apk')) 'Bilingual release notes missing.'
    $head = (Test-Git $checkout @('rev-parse','HEAD')) -join ''
    Assert-True (((Test-Git $remote @('rev-parse','refs/tags/v1.6.1^{}')) -join '') -eq $head) 'Remote tag does not match release commit.'
    Assert-True (((Test-Git $remote @('show','refs/heads/codex/test:Example.java')) -join '') -eq '// Synthetic source') 'New source was not uploaded.'
    Assert-True (@(Test-Git $checkout @('status','--porcelain')).Count -eq 0) 'Checkout is dirty after release.'
    Run-Publisher '1.6.1' 1
    Run-Publisher 'wrong-version' 1
    Assert-True (((Test-Git $checkout @('rev-parse','HEAD')) -join '') -eq $head) 'Rejected input changed HEAD.'

    [IO.File]::WriteAllText((Join-Path $checkout 'private-secret.txt'),'synthetic-only',$utf8)
    Run-Publisher '1.6.2' 1
    Assert-True ([IO.File]::ReadAllText((Join-Path $checkout 'release/version.properties')) -eq $metadata) 'Sensitive-file rejection modified metadata.'
    Remove-Item -LiteralPath (Join-Path $checkout 'private-secret.txt')
    $customNotes = "# Custom release notes`nEnglish / 简体中文`n"
    [IO.File]::WriteAllText((Join-Path $checkout 'release/notes/v1.6.2.md'),$customNotes,$utf8)
    Run-Publisher '1.6.2' 0
    Assert-True ([IO.File]::ReadAllText((Join-Path $checkout 'release/notes/v1.6.2.md')) -eq $customNotes) 'Existing notes were overwritten.'
    $metadata = [IO.File]::ReadAllText((Join-Path $checkout 'release/version.properties'))
    $head = (Test-Git $checkout @('rev-parse','HEAD')) -join ''
    Test-Git $remote @('tag','v1.6.3',$head) | Out-Null
    Run-Publisher '1.6.3' 1
    Assert-True ([IO.File]::ReadAllText((Join-Path $checkout 'release/version.properties')) -eq $metadata) 'Existing remote tag modified metadata.'

    Test-Git $fixture @('clone','--branch','codex/test',$remote,$other) | Out-Null
    Test-Git $other @('config','user.name','Other Test') | Out-Null
    Test-Git $other @('config','user.email','other@example.invalid') | Out-Null
    [IO.File]::WriteAllText((Join-Path $other 'remote-change.txt'),'remote changed',$utf8)
    Test-Git $other @('add','--all') | Out-Null
    Test-Git $other @('commit','-m','Remote source change') | Out-Null
    Test-Git $other @('push','origin','codex/test') | Out-Null
    Run-Publisher '1.6.4' 1
    Assert-True (((Test-Git $checkout @('rev-parse','HEAD')) -join '') -eq $head) 'Diverged branch changed local HEAD.'
    Assert-True ([IO.File]::ReadAllText((Join-Path $checkout 'release/version.properties')) -eq $metadata) 'Diverged branch modified metadata.'
    Test-Git $checkout @('fetch','origin','codex/test') | Out-Null
    Test-Git $checkout @('merge','--ff-only','FETCH_HEAD') | Out-Null
    $remoteBeforeFailure = (Test-Git $remote @('rev-parse','refs/heads/codex/test')) -join ''
    $hook = Join-Path $remote 'hooks/pre-receive'
    [IO.File]::WriteAllText($hook,"#!/bin/sh`nexit 1`n",$utf8)
    if ($env:OS -ne 'Windows_NT') { & chmod +x $hook; if ($LASTEXITCODE -ne 0) { throw 'Cannot enable synthetic hook.' } }
    Run-Publisher '1.6.4' 1
    $failedHead = (Test-Git $checkout @('rev-parse','HEAD')) -join ''
    Assert-True (((Test-Git $checkout @('rev-parse','refs/tags/v1.6.4^{}')) -join '') -eq $failedHead) 'Failed push lost local release tag.'
    Assert-True (((Test-Git $remote @('rev-parse','refs/heads/codex/test')) -join '') -eq $remoteBeforeFailure) 'Rejected atomic push changed remote branch.'
    Assert-True (@(Test-Git $remote @('tag','--list','v1.6.4')).Count -eq 0) 'Rejected atomic push uploaded tag.'
    Remove-Item -LiteralPath $hook
    Test-Git $checkout @('push','--atomic','origin','HEAD:refs/heads/codex/test','refs/tags/v1.6.4') | Out-Null
    Assert-True (((Test-Git $remote @('rev-parse','refs/tags/v1.6.4^{}')) -join '') -eq $failedHead) 'Retry did not publish retained commit.'
    Write-Output 'Release integration checks passed: real atomic push, source inclusion, version/code, bilingual notes, duplicate/invalid versions, private file rejection, custom notes, remote tag collision, remote divergence, rejected atomic push, and retry.'
    Write-Output ('Synthetic local-only fixture: '+$fixture)
} finally {
    $env:JIANVAULT_NO_BROWSER = $previousBrowser
}
