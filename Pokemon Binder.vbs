Dim WshShell, fso, scriptDir
Set WshShell = CreateObject("WScript.Shell")
Set fso = CreateObject("Scripting.FileSystemObject")

' Get the directory of this VBScript file
scriptDir = fso.GetParentFolderName(WScript.ScriptFullName)
WshShell.CurrentDirectory = scriptDir

On Error Resume Next

' Launch the Python server in the background (0 = Hidden, False = Don't wait for completion)
' We use python.exe with 0 (hidden) to run the server completely silently without showing any CMD window.
WshShell.Run "python.exe backend/pokemon_server.py", 0, False

If Err.Number <> 0 Then
    MsgBox "Error: Could not launch python.exe." & vbCrLf & _
           "Please ensure Python is installed and added to your system PATH.", _
           16, "Pokémon Binder"
Else
    ' Wait 3 seconds for the server to load its database cache and bind to port 8000
    WScript.Sleep 3000
    
    ' Open Microsoft Edge in standalone chromeless App Mode
    ' Spawns a clean dedicated desktop window without tabs or an address bar
    WshShell.Run "msedge --app=http://localhost:8000 --window-size=1320,880", 1, False
End If
