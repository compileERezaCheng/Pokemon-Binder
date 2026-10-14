; Professional Inno Setup Script for Pokemon Binder Manager
; Download Inno Setup from: https://jrsoftware.org/isdl.php

[Setup]
AppId={{D3F9B7E1-8C5A-4A1B-B0E3-5B2D1C5E6F7A}}
AppName=Pokemon Binder Manager
AppVersion=1.5.1
DefaultDirName={autopf}\PokemonBinder
DefaultGroupName=Pokemon Binder Manager
AllowNoIcons=yes
OutputDir=.
OutputBaseFilename=Pokemon_Binder_Setup
Compression=lzma
SolidCompression=yes
WizardStyle=modern
SetupIconFile=data\pokeball.ico

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"
Name: "portuguese"; MessagesFile: "compiler:Languages\Portuguese.isl"

[Files]
; Install the server that stays in the tray and opens the binder in the browser.
Source: "dist\PokemonBinderServer\*"; DestDir: "{app}\PokemonBinderServer"; Flags: ignoreversion recursesubdirs createallsubdirs
Source: "data\pokeball.ico"; DestDir: "{app}"; Flags: ignoreversion

[InstallDelete]
Type: files; Name: "{autodesktop}\Pokemon Binder Manager.lnk"

[Icons]
Name: "{group}\Pokemon Binder Manager"; Filename: "{app}\PokemonBinderServer\PokemonBinderServer.exe"; IconFilename: "{app}\pokeball.ico"
Name: "{autodesktop}\Pokemon Binder Manager"; Filename: "{app}\PokemonBinderServer\PokemonBinderServer.exe"; IconFilename: "{app}\pokeball.ico"

[Run]
Filename: "{app}\PokemonBinderServer\PokemonBinderServer.exe"; Description: "{cm:LaunchProgram,Pokemon Binder Manager}"; Flags: nowait postinstall skipifsilent
