$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
$firebaseApiKey = $env:POKEBINDER_FIREBASE_API_KEY
$firebaseDbUrl = $env:POKEBINDER_FIREBASE_DB_URL
if ([string]::IsNullOrWhiteSpace($firebaseApiKey) -or [string]::IsNullOrWhiteSpace($firebaseDbUrl)) {
    throw 'Set POKEBINDER_FIREBASE_API_KEY and POKEBINDER_FIREBASE_DB_URL for the beta build.'
}
if ($firebaseDbUrl -notmatch '^https://[^/]+/?$') { throw 'POKEBINDER_FIREBASE_DB_URL must be the Firebase Realtime Database HTTPS URL.' }
$firebaseConfigDirectory = Join-Path ([IO.Path]::GetTempPath()) ("pokebinder-build-" + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $firebaseConfigDirectory | Out-Null
$firebaseConfigPath = Join-Path $firebaseConfigDirectory 'firebase_client_config.json'
[ordered]@{ apiKey = $firebaseApiKey; databaseURL = $firebaseDbUrl } |
    ConvertTo-Json -Compress |
    Set-Content -LiteralPath $firebaseConfigPath -Encoding utf8NoBOM
try {
    python -m PyInstaller --noconfirm --noconsole --onedir --name PokemonBinderServer --icon="$PSScriptRoot\data\pokeball.ico" --distpath dist --workpath build --specpath build --exclude-module backend.firebase_secrets --exclude-module firebase_secrets --add-data "$PSScriptRoot\frontend;frontend" --add-data "$PSScriptRoot\data\pokeball.ico;data" --add-data "$PSScriptRoot\data\pokemon.png;data" --add-data "$PSScriptRoot\data\binder_config.json.default;data" --add-data "$PSScriptRoot\data\pokemon_binder.csv.default;data" --add-data "$firebaseConfigPath;data" backend/pokemon_server.py
    if ($LASTEXITCODE -ne 0) { throw 'PyInstaller falhou' }
} finally {
    Remove-Item -LiteralPath $firebaseConfigPath -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $firebaseConfigDirectory -Force -ErrorAction SilentlyContinue
}
dotnet publish src/PokemonBinder.csproj --configuration Release --runtime win-x64 --self-contained true --property:PublishSingleFile=true --output "$PSScriptRoot\build\desktop"
if ($LASTEXITCODE -ne 0) { throw 'Publicação da aplicação Windows falhou' }
Copy-Item "$PSScriptRoot\build\desktop\*" "$PSScriptRoot\dist" -Recurse -Force
Write-Host 'Pronto: dist/Pokemon Binder.exe e dist/PokemonBinderServer/PokemonBinderServer.exe'
