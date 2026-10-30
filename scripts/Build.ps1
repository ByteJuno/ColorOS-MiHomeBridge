param([string]$JavaRoot = $env:JAVA_HOME)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location -LiteralPath $projectRoot
try {
    if (-not $JavaRoot) { throw 'Set JAVA_HOME to JDK 21 or pass -JavaRoot.' }
    $keytool = Join-Path $JavaRoot 'bin\keytool.exe'
    if (-not (Test-Path -LiteralPath $keytool)) { throw 'JDK keytool not found.' }
    $signingDir = Join-Path $projectRoot '.local\signing'
    New-Item -ItemType Directory -Path $signingDir -Force | Out-Null
    $configFile = Join-Path $signingDir 'signing.properties'
    $keystore = Join-Path $signingDir 'module.p12'
    if (-not (Test-Path -LiteralPath $configFile)) {
        if (Test-Path -LiteralPath $keystore) { throw 'Keystore exists without its configuration. Restore signing.properties; do not replace the key.' }
        $secretBytes = New-Object byte[] 32
        $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
        try { $rng.GetBytes($secretBytes) } finally { $rng.Dispose() }
        $env:MIHOME_BRIDGE_KEY_PASSWORD = [Convert]::ToBase64String($secretBytes)
        try {
            & $keytool -genkeypair -keystore $keystore -storetype PKCS12 -alias mihomebridge -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=ColorOS MiHome Bridge' -storepass:env MIHOME_BRIDGE_KEY_PASSWORD -keypass:env MIHOME_BRIDGE_KEY_PASSWORD
            if ($LASTEXITCODE -ne 0) { throw 'Key generation failed.' }
            @(
                'storeFile=.local/signing/module.p12'
                ('storePassword=' + $env:MIHOME_BRIDGE_KEY_PASSWORD)
                'keyAlias=mihomebridge'
                ('keyPassword=' + $env:MIHOME_BRIDGE_KEY_PASSWORD)
            ) | Set-Content -LiteralPath $configFile -Encoding ascii
        } finally { Remove-Item Env:MIHOME_BRIDGE_KEY_PASSWORD -ErrorAction SilentlyContinue }
    }
    $env:JAVA_HOME = $JavaRoot
    & '.\gradlew.bat' :app:testDebugUnitTest :app:lintDebug :app:assembleRelease --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'Build or verification failed.' }
    New-Item -ItemType Directory -Path 'dist' -Force | Out-Null
    $metadata = Get-Content -LiteralPath 'app\build\outputs\apk\release\output-metadata.json' -Raw | ConvertFrom-Json
    $apkName = 'ColorOS-MiHomeBridge-' + $metadata.elements[0].versionName + '.apk'
    Copy-Item -LiteralPath 'app\build\outputs\apk\release\app-release.apk' -Destination (Join-Path 'dist' $apkName) -Force
    $hash = (Get-FileHash -LiteralPath (Join-Path 'dist' $apkName) -Algorithm SHA256).Hash.ToLowerInvariant()
    "$hash  $apkName" | Set-Content -LiteralPath 'dist\SHA256SUMS.txt' -Encoding ascii
    Write-Output ('Built dist\' + $apkName)
} finally { Pop-Location }
