#Requires -Version 5.1
<#
.SYNOPSIS
    Pull a host APK from a rooted device and dump the bytecode of the classes the
    bridge depends on.

.DESCRIPTION
    check_firmware.py only proves that a method exists. Reconstructing what the 27
    PanelDevice arguments actually mean needs the field assignment order inside
    PanelDevice.<init>, so this script extracts the relevant classes as
    `dexdump -d` output.

    Nothing is executed on the device: APKs are copied out, zipped open read-only,
    and only the class sections named in -Classes are written.

.EXAMPLE
    .\dump_host_dex.ps1 -Package com.android.systemui
    .\dump_host_dex.ps1 -Package com.heytap.mydevices -All
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$Package,
    [string]$Adb = 'D:\Android\Sdk\platform-tools\adb.exe',
    [string]$Sdk = 'D:\Android\Sdk',
    [string]$OutputDir,
    # APKs are ~100 MB each and SystemUI alone takes two minutes over adb; keep them.
    [string]$ApkCache,
    # Default: the device-space surface. -All dumps the MyDevices card classes too.
    [string[]]$Classes,
    # One class descriptor per line; -File cannot pass arrays from powershell.exe.
    [string]$ClassesFile,
    [switch]$All
)

$ErrorActionPreference = 'Stop'
# $PSScriptRoot is empty inside the param block on Windows PowerShell 5.1.
if (-not $OutputDir) { $OutputDir = Join-Path $PSScriptRoot '..\docs\host-dex' }
if (-not $ApkCache) { $ApkCache = Join-Path $PSScriptRoot '..\.local\apk' }
if ($ClassesFile) {
    if (-not (Test-Path $ClassesFile)) { throw "classes file not found: $ClassesFile" }
    $Classes = @(Get-Content $ClassesFile | Where-Object { $_.Trim() } | ForEach-Object { $_.Trim() })
}

function Find-Dexdump {
    param([string]$SdkRoot)
    $buildTools = Join-Path $SdkRoot 'build-tools'
    if (-not (Test-Path $buildTools)) { throw "build-tools not found under $SdkRoot" }
    $exe = Get-ChildItem $buildTools -Directory |
        Sort-Object { [version]($_.Name -replace '[^0-9.].*$', '') } -Descending |
        ForEach-Object { Join-Path $_.FullName 'dexdump.exe' } |
        Where-Object { Test-Path $_ } |
        Select-Object -First 1
    if (-not $exe) { throw "dexdump.exe not found under $buildTools" }
    return $exe
}

function Invoke-Adb {
    param([string[]]$Arguments)
    $output = & $script:AdbPath @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw "adb $($Arguments -join ' ') failed: $output" }
    return $output
}

function Invoke-Native {
    param([string]$File, [string[]]$Arguments)
    # adb writes progress and warnings to stderr. Under $ErrorActionPreference = 'Stop'
    # a merged stderr stream becomes a terminating NativeCommandError, so relax it here.
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = & $File @Arguments 2>&1
        return [pscustomobject]@{ ExitCode = $LASTEXITCODE; Output = (@($output) -join "`n") }
    } finally { $ErrorActionPreference = $previous }
}

function Get-RemoteApks {
    param([string]$Name)
    $result = Invoke-Native -File $script:AdbPath -Arguments @('shell', "pm path $Name")
    if ($result.ExitCode -ne 0) { throw "pm path $Name failed: $($result.Output)" }
    $apks = @($result.Output -split "`n" | Where-Object { $_ -match '^package:' } |
        ForEach-Object { $_.Substring(8).Trim() })
    if (-not $apks) { throw "no APK reported for $Name" }
    return $apks
}

function Save-RemoteApk {
    param([string]$Remote, [string]$Local)
    if (Test-Path $Local) { Write-Host "cached: $Local"; return $false }
    $name = [IO.Path]::GetFileName($Remote)
    $staging = "/data/local/tmp/mihomebridge-$name"
    # cp leaves root-owned 600 files that the shell user cannot pull; make them readable.
    $staged = Invoke-Native -File $script:AdbPath -Arguments @('shell',
        "su -c 'cp `"$Remote`" $staging && chmod 644 $staging'")
    if ($staged.ExitCode -ne 0) { throw "cannot stage $Remote (root required): $($staged.Output)" }
    $pulled = Invoke-Native -File $script:AdbPath -Arguments @('pull', $staging, $Local)
    Invoke-Native -File $script:AdbPath -Arguments @('shell', "su -c 'rm -f $staging'") | Out-Null
    if ($pulled.ExitCode -ne 0 -or -not (Test-Path $Local)) { throw "pull failed for ${Remote}: $($pulled.Output)" }
    return $true
}

# Writes the dexdump section of every requested class found in one dex file.
function Export-ClassSections {
    param([string]$DexPath, [string]$Dexdump, [string[]]$Wanted, [string]$Destination)

    $remaining = [Collections.Generic.HashSet[string]]::new([string[]]$Wanted)
    $startInfo = [Diagnostics.ProcessStartInfo]::new($Dexdump)
    $startInfo.Arguments = "-d `"$DexPath`""
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true

    $process = [Diagnostics.Process]::Start($startInfo)
    try {
        $writer = $null
        $current = $null
        while ($null -ne ($line = $process.StandardOutput.ReadLine())) {
            if ($line -match "^\s*Class descriptor\s+:\s*'(.+)'\s*$") {
                if ($null -ne $writer) { $writer.Dispose(); $writer = $null; $current = $null }
                $descriptor = $Matches[1]
                if ($remaining.Contains($descriptor)) {
                    $current = $descriptor
                    $name = ($descriptor -replace '^L|;$', '') -replace '/', '.'
                    $writer = [IO.StreamWriter]::new((Join-Path $Destination "$name.txt"), $false, [Text.UTF8Encoding]::new($false))
                    [void]$remaining.Remove($descriptor)
                }
            }
            if ($null -ne $writer) { $writer.WriteLine($line) }
            if ($remaining.Count -eq 0) { break }
        }
        if ($null -ne $writer) { $writer.Dispose() }
    } finally {
        if (-not $process.HasExited) { $process.Kill() }
        $process.Dispose()
    }
    return @($remaining)
}

if (-not (Test-Path $Adb)) { throw "adb not found: $Adb" }
$script:AdbPath = (Resolve-Path $Adb).Path
$dexdump = Find-Dexdump -SdkRoot $Sdk

if (-not $Classes) {
    $Classes = @(
        'Lcom/oplus/deviceplugin/sdk/entity/PanelDevice;',
        'Lcom/oplus/deviceplugin/sdk/entity/DeviceStatus;',
        'Lcom/oplus/deviceplugin/sdk/entity/PanelInfo;',
        'Lcom/oplus/deviceplugin/sdk/ui/view/separatecardview/SquareDeviceCardView;',
        'Lcom/oplus/deviceplugin/sdk/ui/view/separatecardview/RectangleDeviceCardView;',
        'Lcom/oplus/deviceplugin/sdk/ui/view/d;',
        'Lcom/oplus/deviceplugin/sdk/utils/ViewExtKt;',
        'Lcom/oplus/deviceplugin/sdk/presenter/l;'
    )
    if ($All) {
        $Classes += @(
            'Lcom/oplus/mydevices/domain/entities/cards/QuickCardDeviceData;',
            'Lcom/oplus/mydevices/deviceui/devicecard/view/homecard/HomeCardView;',
            'Lcom/oplus/mydevices/domain/entities/plugin/DeviceStatus;',
            'Lcom/oplus/mydevices/domain/entities/cards/StatusRing;',
            'Lcom/oplus/mydevices/domain/entities/device/BatteryInfo;'
        )
    }
}

New-Item -ItemType Directory -Force -Path $OutputDir, $ApkCache | Out-Null
# dexdump is an old C++ tool that fails on non-ASCII paths ("ReadFileToString failed"),
# so the dex files have to live somewhere ASCII even when the user profile is not.
$asciiTemp = Join-Path $env:SystemRoot 'Temp'
if ($asciiTemp -notmatch '^[\x20-\x7E]+$') { $asciiTemp = 'C:\Windows\Temp' }
$work = Join-Path $asciiTemp ("host-dex-" + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $work | Out-Null

try {
    $report = [Collections.Generic.List[object]]::new()
    foreach ($remote in Get-RemoteApks -Name $Package) {
        $local = Join-Path $ApkCache ([IO.Path]::GetFileName($remote))
        $downloaded = Save-RemoteApk -Remote $remote -Local $local
        $hash = (Get-FileHash -Algorithm SHA256 -Path $local).Hash.ToLowerInvariant()
        $report += [pscustomobject]@{ apk = $remote; bytes = (Get-Item $local).Length; sha256 = $hash; cached = (-not $downloaded) }

        $expanded = Join-Path $work ([IO.Path]::GetFileNameWithoutExtension($local))
        New-Item -ItemType Directory -Force -Path $expanded | Out-Null
        # Only the dex entries matter; extracting a whole 100 MB APK is pointless here.
        Add-Type -AssemblyName System.IO.Compression.FileSystem
        $archive = [IO.Compression.ZipFile]::OpenRead($local)
        try {
            foreach ($entry in $archive.Entries | Where-Object { $_.Name -match '^classes\d*\.dex$' }) {
                [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, (Join-Path $expanded $entry.Name), $true)
            }
        } finally { $archive.Dispose() }

        foreach ($dex in Get-ChildItem $expanded -Filter 'classes*.dex' | Sort-Object Name) {
            # Skip dex files that cannot contain a wanted descriptor; dexdump on a
            # 20 MB dex is slow and we usually only care about one of them.
            $text = [IO.File]::ReadAllText($dex.FullName, [Text.Encoding]::GetEncoding(28591))
            if (-not ($Classes | Where-Object { $text.Contains($_.TrimStart('L').TrimEnd(';')) })) { continue }
            $text = $null
            Write-Host "dumping $($dex.Name) of $([IO.Path]::GetFileName($local))"
            $missing = Export-ClassSections -DexPath $dex.FullName -Dexdump $dexdump -Wanted $Classes -Destination $OutputDir
            if ($missing.Count -eq 0) { break }
        }
    }

    Write-Host ''
    $report | Format-Table -AutoSize | Out-String -Width 200 | Write-Host
    $dumped = Get-ChildItem $OutputDir -Filter '*.txt' | Select-Object -ExpandProperty Name
    Write-Host "classes in ${OutputDir}:"
    $dumped | ForEach-Object { Write-Host "  $_" }
    $absent = $Classes | Where-Object { $dumped -notcontains ((($_ -replace '^L|;$', '') -replace '/', '.') + '.txt') }
    if ($absent) { Write-Host "not found: $($absent -join ', ')" -ForegroundColor Yellow }
}
finally {
    Remove-Item $work -Recurse -Force -ErrorAction SilentlyContinue
}
