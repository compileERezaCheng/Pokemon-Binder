using System;
using System.Diagnostics;
using System.IO;
using System.Windows.Forms;

class Program
{
    [STAThread]
    static void Main()
    {
        string appDir = AppDomain.CurrentDomain.BaseDirectory;
        Directory.SetCurrentDirectory(appDir);

        // 1. Launch the Python server windowlessly
        try
        {
            ProcessStartInfo serverInfo = new ProcessStartInfo();
            serverInfo.FileName = "python.exe";
            serverInfo.Arguments = "backend/pokemon_server.py";
            serverInfo.UseShellExecute = false;
            serverInfo.CreateNoWindow = true;
            serverInfo.WindowStyle = ProcessWindowStyle.Hidden;
            Process.Start(serverInfo);
        }
        catch (Exception ex)
        {
            MessageBox.Show("Error: Could not launch python.exe.\n\nPlease ensure Python is installed and added to your system PATH.\n\nDetails: " + ex.Message, 
                "Pokémon Binder", MessageBoxButtons.OK, MessageBoxIcon.Error);
            return;
        }

        // Wait 3 seconds for server startup and icon download
        System.Threading.Thread.Sleep(3000);

        // 2. Desktop Shortcut prompt on first run
        try
        {
            string desktopPath = Environment.GetFolderPath(Environment.SpecialFolder.Desktop);
            string shortcutPath = Path.Combine(desktopPath, "Pokemon Binder.lnk");

            if (!File.Exists(shortcutPath))
            {
                DialogResult result = MessageBox.Show(
                    "Do you want to create a Desktop shortcut for Pokémon Binder Manager?",
                    "Pokémon Binder Setup",
                    MessageBoxButtons.YesNo,
                    MessageBoxIcon.Question
                );

                if (result == DialogResult.Yes)
                {
                    CreateShortcut(shortcutPath, appDir);
                }
            }
        }
        catch (Exception)
        {
            // Fail silently if shortcut creation fails
        }

        // 3. Launch Edge in App Mode
        try
        {
            ProcessStartInfo edgeInfo = new ProcessStartInfo();
            edgeInfo.FileName = "msedge.exe";
            edgeInfo.Arguments = "--app=http://localhost:8080 --window-size=1320,880";
            edgeInfo.UseShellExecute = true;
            Process.Start(edgeInfo);
        }
        catch (Exception)
        {
            // If Edge isn't found by direct executable name, let Windows shell open the URL in default browser
            try
            {
                Process.Start("http://localhost:8080");
            }
            catch (Exception) { }
        }
    }

    static void CreateShortcut(string shortcutPath, string appDir)
    {
        // Use COM WScript.Shell object to create a shortcut dynamically in C# without external library dependencies
        Type shellType = Type.GetTypeFromProgID("WScript.Shell");
        dynamic shell = Activator.CreateInstance(shellType);
        dynamic shortcut = shell.CreateShortcut(shortcutPath);

        shortcut.TargetPath = Path.Combine(appDir, "Pokemon Binder.exe");
        shortcut.WorkingDirectory = appDir;
        shortcut.Description = "Launch Pokémon Binder Manager";

        string iconPath = Path.Combine(appDir, "data", "pokeball.ico");
        if (File.Exists(iconPath))
        {
            shortcut.IconLocation = iconPath;
        }

        shortcut.Save();
    }
}
