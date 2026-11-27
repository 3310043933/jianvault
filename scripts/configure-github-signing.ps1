param([Parameter(Mandatory)][string]$SigningProperties,[string]$Repository='3310043933/jianvault')
$ErrorActionPreference = 'Stop'
if (-not (Get-Command gh -ErrorAction SilentlyContinue)) { throw 'Install GitHub CLI and run gh auth login first.' }
$values = @{}
foreach ($line in [IO.File]::ReadAllLines((Resolve-Path -LiteralPath $SigningProperties).Path)) {
    if ($line -match '^\s*(#|$)') { continue }; $parts = $line.Split('=',2); if ($parts.Count -eq 2) { $values[$parts[0].Trim()] = $parts[1].Trim() }
}
foreach ($key in @('storeFile','storePassword','keyAlias','keyPassword')) { if (-not $values[$key]) { throw ('Missing signing property: '+$key) } }
$secrets = @{
    JIANVAULT_KEYSTORE_BASE64=[Convert]::ToBase64String([IO.File]::ReadAllBytes($values['storeFile']))
    JIANVAULT_STORE_PASSWORD=$values['storePassword']
    JIANVAULT_KEY_ALIAS=$values['keyAlias']
    JIANVAULT_KEY_PASSWORD=$values['keyPassword']
}
try {
    foreach ($entry in $secrets.GetEnumerator()) {
        $start = [Diagnostics.ProcessStartInfo]::new((Get-Command gh).Source)
        $start.UseShellExecute = $false; $start.CreateNoWindow = $true; $start.RedirectStandardInput = $true
        foreach ($argument in @('secret','set',$entry.Key,'--repo',$Repository)) { $start.ArgumentList.Add($argument) }
        $process = [Diagnostics.Process]::Start($start)
        $process.StandardInput.Write($entry.Value); $process.StandardInput.Close(); $process.WaitForExit()
        if ($process.ExitCode -ne 0) { throw ('Unable to set GitHub secret: '+$entry.Key) }
        $process.Dispose()
        Write-Output ('Configured repository secret: '+$entry.Key)
    }
} finally { $secrets.Clear(); $values.Clear() }
