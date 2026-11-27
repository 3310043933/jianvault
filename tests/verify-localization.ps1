param([string]$ProjectDirectory = (Split-Path -Parent $PSScriptRoot))
$ErrorActionPreference = 'Stop'
$sourceDir = Join-Path $ProjectDirectory 'app\src\main\java\cn\local\simpleauth'
[xml]$en = [IO.File]::ReadAllText((Join-Path $ProjectDirectory 'app\src\main\res\values\strings.xml'))
[xml]$zh = [IO.File]::ReadAllText((Join-Path $ProjectDirectory 'app\src\main\res\values-zh\strings.xml'))
$english = @{}; $chinese = @{}
foreach ($pair in @(@($en,$english),@($zh,$chinese))) {
    foreach ($node in $pair[0].resources.string) {
        $id = [string]$node.name
        if ($pair[1].ContainsKey($id)) { throw ('Duplicate string resource: '+$id) }
        $pair[1][$id] = [string]$node.InnerText
    }
}
if (@(Compare-Object @($english.Keys | Sort-Object) @($chinese.Keys | Sort-Object)).Count -ne 0) { throw 'English and Chinese resource keys differ.' }
foreach ($id in $english.Keys) {
    $left = @([regex]::Matches($english[$id], '%\d+\$[ds]') | ForEach-Object Value | Sort-Object)
    $right = @([regex]::Matches($chinese[$id], '%\d+\$[ds]') | ForEach-Object Value | Sort-Object)
    if (($left -join '|') -ne ($right -join '|')) { throw ('Format placeholders differ: '+$id) }
    if ($english[$id] -match '[\p{IsCJKUnifiedIdeographs}]' -and $id -notin @('language_setting','language_system','language_native_zh')) { throw ('Untranslated English UI resource: '+$id) }
}
$catalogSource = [IO.File]::ReadAllText((Join-Path $sourceDir 'LanguageCatalog.java'))
$catalogBody = [regex]::Match($catalogSource,'CODES = \{([^}]+)\}').Groups[1].Value
$codes = @([regex]::Matches($catalogBody,'"([^"]+)"') | ForEach-Object { $_.Groups[1].Value })
[xml]$nativeLabels = [IO.File]::ReadAllText((Join-Path $ProjectDirectory 'app/src/main/res/values/languages.xml'))
$labels = @($nativeLabels.resources.'string-array'.item)
if ($codes.Count -ne 21 -or $labels.Count -ne $codes.Count) { throw 'Language codes and native labels differ.' }
$folders = @{en='values';zh='values-zh';'zh-Hant'='values-b+zh+Hant';id='values-in'}
foreach ($code in $codes) {
    $folder = if ($folders.ContainsKey($code)) { $folders[$code] } else { 'values-'+$code }
    $resourcePath = Join-Path $ProjectDirectory ('app/src/main/res/'+$folder+'/strings.xml')
    [xml]$localeXml = [IO.File]::ReadAllText($resourcePath)
    $localized = @{}
    foreach ($node in $localeXml.resources.string) {
        $id = [string]$node.name
        if ($localized.ContainsKey($id)) { throw ('Duplicate resource: '+$code+' '+$id) }
        $localized[$id] = [string]$node.InnerText
        if (-not [string]::IsNullOrWhiteSpace($english[$id].Trim('"')) -and [string]::IsNullOrWhiteSpace($localized[$id].Trim('"'))) { throw ('Empty translation: '+$code+' '+$id) }
        if ($localized[$id] -match '__JV_|ZXQITEM') { throw ('Unrestored translation token: '+$code+' '+$id) }
    }
    if (@(Compare-Object @($english.Keys | Sort-Object) @($localized.Keys | Sort-Object)).Count) { throw ('Incomplete locale: '+$code) }
    foreach ($id in $english.Keys) {
        $expected = @([regex]::Matches($english[$id], '%\d+\$[ds]') | ForEach-Object Value | Sort-Object)
        $actual = @([regex]::Matches($localized[$id], '%\d+\$[ds]') | ForEach-Object Value | Sort-Object)
        if (($expected -join '|') -cne ($actual -join '|')) { throw ('Locale format mismatch: '+$code+' '+$id) }
    }
}
foreach ($file in @('MainActivity.java','DrawerHost.java','SecureCaptureActivity.java','AppLanguage.java')) {
    $source = [IO.File]::ReadAllText((Join-Path $sourceDir $file))
    foreach ($match in [regex]::Matches($source, '"(?:\\.|[^"\\])*"')) {
        if ($match.Value -match '[\p{IsCJKUnifiedIdeographs}]') { throw ('Hardcoded Chinese UI in '+$file+': '+$match.Value) }
    }
}
$errorMapSource = [IO.File]::ReadAllText((Join-Path $sourceDir 'ErrorMessages.java'))
$mapped = @{}
foreach($match in [regex]::Matches($errorMapSource,'ENGLISH\.put\(("(?:\\.|[^"\\])*"),')) { $mapped[(ConvertFrom-Json $match.Groups[1].Value)] = $true }
$resourceKeys = @([regex]::Matches($errorMapSource,'RESOURCE_KEYS\.put\("(?:\\.|[^"\\])*",\s*"([^"]+)"\)') | ForEach-Object { $_.Groups[1].Value })
if ($resourceKeys.Count -ne $mapped.Count -or @($resourceKeys | Sort-Object -Unique).Count -ne $mapped.Count) { throw 'Validation error resource mapping differs.' }
foreach ($key in $resourceKeys) { if (-not $english.ContainsKey($key)) { throw ('Missing validation resource: '+$key) } }
foreach ($file in Get-ChildItem -LiteralPath $sourceDir -Filter '*.java') {
    $source = [IO.File]::ReadAllText($file.FullName)
    foreach($match in [regex]::Matches($source,'throw new [\w.]+\(\s*("(?:\\.|[^"\\])*")')) {
        $message = ConvertFrom-Json $match.Groups[1].Value
        if ($message -match '[\p{IsCJKUnifiedIdeographs}]' -and -not $mapped.ContainsKey($message)) { throw ('Missing English error translation: '+$message) }
    }
}
foreach ($pair in @(@('README.md','README.zh-CN.md'),@('AGENTS.md','AGENTS.zh-CN.md'),@('THIRD_PARTY.md','THIRD_PARTY.zh-CN.md'))) {
    $left = [IO.File]::ReadAllText((Join-Path $ProjectDirectory $pair[0])); $right = [IO.File]::ReadAllText((Join-Path $ProjectDirectory $pair[1]))
    if (-not $left.Contains(']('+$pair[1]+')') -or -not $right.Contains(']('+$pair[0]+')')) { throw ('Missing language switch links for '+$pair[0]) }
}
Write-Output ('PASS: '+$english.Count+' resource keys across '+$codes.Count+' locales, format arguments, '+$mapped.Count+' translated errors and 3 bilingual document pairs.')
