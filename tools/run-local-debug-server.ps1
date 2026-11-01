param()
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $projectRoot
$localData = Join-Path $projectRoot '.runtime/local-debug'
New-Item -ItemType Directory -Force -Path $localData | Out-Null
$env:APP_DATABASE_URL = 'sqlite+pysqlite:///' + ((Join-Path $localData 'metadata.sqlite3') -replace '\\', '/')
$env:APP_STORAGE_ROOT = $localData
$env:APP_SEED_DEMO = 'true'
$env:APP_SMS_ENABLED = 'false'
$env:PYTHONUTF8 = '1'
$recognitionEnv = Join-Path $projectRoot '.runtime/recognition.local.env'
if (Test-Path -LiteralPath $recognitionEnv) {
    foreach ($line in Get-Content -LiteralPath $recognitionEnv) {
        if ($line -match '^(APP_RECOGNITION_(PYTHON|MANIFEST))=(.+)$') {
            [Environment]::SetEnvironmentVariable($Matches[1], $Matches[3], 'Process')
        }
    }
}
$detectorManifest = Join-Path $projectRoot 'server/config/detector.json'
if (Test-Path -LiteralPath $detectorManifest) {
    $detector = Get-Content -Raw -LiteralPath $detectorManifest | ConvertFrom-Json
    if ((Test-Path -LiteralPath $detector.python) -and (Test-Path -LiteralPath $detector.weights)) {
        $env:APP_RECOGNITION_DETECT_MANIFEST = $detectorManifest
    }
}
Write-Host 'Local API: http://127.0.0.1:18080; isolated local data; SMS disabled.'
& "$projectRoot/server/.venv/Scripts/python.exe" -m uvicorn server.app.main:app --host 127.0.0.1 --port 18080
exit $LASTEXITCODE
