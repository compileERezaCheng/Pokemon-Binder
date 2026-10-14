$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
python -m PyInstaller --noconfirm --noconsole --onedir --name PokemonBinderServer --icon="$PSScriptRoot\data\pokeball.ico" --distpath dist --workpath build --specpath build --add-data "$PSScriptRoot\frontend;frontend" --add-data "$PSScriptRoot\data;data" backend/pokemon_server.py
if ($LASTEXITCODE -ne 0) { throw 'PyInstaller falhou' }
Write-Host 'Pronto: dist/PokemonBinderServer/PokemonBinderServer.exe'
