Dim WshShell, fso, scriptDir
Set WshShell = CreateObject("WScript.Shell")
Set fso = CreateObject("Scripting.FileSystemObject")

' Get the directory of this VBScript file
scriptDir = fso.GetParentFolderName(WScript.ScriptFullName)
' Change current directory to the script's folder
WshShell.CurrentDirectory = scriptDir

On Error Resume Next

' Launch the Python server in the background (0 = Hidden, False = Don't wait for completion)
WshShell.Run "pythonw.exe pokemon_server.py", 0, False

If Err.Number <> 0 Then
    MsgBox "Error: Could not launch pythonw.exe." & vbCrLf & _
           "Please ensure Python is installed and added to your system PATH.", _
           16, "Pokémon Binder Server"
Else
    ' Wait 2 seconds for the server to bind to the port
    WScript.Sleep 2000
    ' Open the browser to localhost:8000 directly (no cmd window)
    WshShell.Run "http://localhost:8000"
End If
