$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
python -m PyInstaller --noconfirm --noconsole --onedir --name PokemonBinderServer --distpath dist --workpath build --specpath build --add-data "$PSScriptRoot\frontend;frontend" --add-data "$PSScriptRoot\data;data" backend/pokemon_server.py
if ($LASTEXITCODE -ne 0) { throw 'PyInstaller falhou' }
dotnet publish src/PokemonBinder.csproj -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true -o dist
if ($LASTEXITCODE -ne 0) { throw 'dotnet publish falhou' }
Write-Host 'Pronto: dist/Pokemon Binder.exe'
