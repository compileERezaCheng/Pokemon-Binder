Set WshShell = CreateObject("WScript.Shell")
On Error Resume Next

' Launch the Python server in the background (0 = Hidden, False = Don't wait for completion)
WshShell.Run "pythonw.exe pokemon_server.py", 0, False

If Err.Number <> 0 Then
    MsgBox "Error: Could not launch pythonw.exe." & vbCrLf & _
           "Please ensure Python is installed and added to your system PATH.", _
           16, "Pokémon Binder Server"
Else
    ' Wait half a second for the server to bind to the port
    WScript.Sleep 500
    ' Open the browser to localhost:8000
    WshShell.Run "cmd.exe /c start http://localhost:8000", 0, False
End If
