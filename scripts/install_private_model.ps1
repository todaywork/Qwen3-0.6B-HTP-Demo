param(
    [Parameter(Mandatory=$true)][string]$Serial,
    [ValidateSet('v68','v73')][string]$Channel = 'v68',
    [string]$Adb = 'adb'
)
$ErrorActionPreference = 'Stop'
$androidUser = (& $Adb -s $Serial shell am get-current-user).Trim()
if ($LASTEXITCODE -ne 0 -or $androidUser -notmatch '^\d+$') {
    throw 'Cannot determine current Android user'
}
$runAs = "run-as com.qairt.qwen3htp --user $androidUser"
& $Adb -s $Serial shell "$runAs mkdir -p files/models/$Channel"
if ($LASTEXITCODE -ne 0) { throw 'Install the Debug APK first; run-as is required' }
& $Adb -s $Serial shell "tar -C /data/local/tmp/genie_qwen3_quality -cf - tokenizer.json htp_backend_ext_config.json part1_of_2.bin part2_of_2.bin | $runAs tar -C files/models/$Channel -xf -"
if ($LASTEXITCODE -ne 0) { throw 'Model copy failed' }
& $Adb -s $Serial shell "$runAs ls -l files/models/$Channel"
if ($LASTEXITCODE -ne 0) { throw 'Model directory check failed' }
Write-Host "Model copied to private directory for $Channel, Android user $androidUser. Restart the app."
