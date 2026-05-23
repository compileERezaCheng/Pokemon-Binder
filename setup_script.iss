; Professional Inno Setup Script for Pokemon Binder Manager
; Download Inno Setup from: https://jrsoftware.org/isdl.php

[Setup]
AppId={{D3F9B7E1-8C5A-4A1B-B0E3-5B2D1C5E6F7A}}
AppName=Pokemon Binder Manager
AppVersion=1.0.0
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

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"; Flags: checkedonce

[Files]
; The main application files from the dist/Pokemon Binder folder
Source: "dist\Pokemon Binder\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{group}\Pokemon Binder Manager"; Filename: "{app}\Pokemon Binder.exe"
Name: "{autodesktop}\Pokemon Binder Manager"; Filename: "{app}\Pokemon Binder.exe"; Tasks: desktopicon

[Run]
Filename: "{app}\Pokemon Binder.exe"; Description: "{cm:LaunchProgram,Pokemon Binder Manager}"; Flags: nowait postinstall skipifsilent
