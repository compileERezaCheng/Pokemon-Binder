using System;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Net.Http;
using System.Threading.Tasks;
using System.Windows.Forms;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.WinForms;

internal static class Program
{
    [STAThread]
    private static void Main()
    {
        Application.EnableVisualStyles();
        Application.SetCompatibleTextRenderingDefault(false);
        Application.Run(new BinderWindow());
    }
}

internal sealed class BinderWindow : Form
{
    private const string Address = "http://127.0.0.1:8080";
    private readonly WebView2 browser = new() { Dock = DockStyle.Fill };
    private Process? server;

    public BinderWindow()
    {
        Text = "Pokémon Binder";
        MinimumSize = new Size(900, 600);
        Size = new Size(1320, 880);
        StartPosition = FormStartPosition.CenterScreen;
        Icon = Icon.ExtractAssociatedIcon(Application.ExecutablePath);
        Controls.Add(browser);
        Shown += async (_, _) => await StartAsync();
        FormClosed += (_, _) => StopServer();
    }

    private async Task StartAsync()
    {
        try
        {
            string token = Guid.NewGuid().ToString("N");
            string appDir = AppContext.BaseDirectory;
            string packaged = Path.Combine(appDir, "PokemonBinderServer", "PokemonBinderServer.exe");
            string script = Path.Combine(Directory.GetCurrentDirectory(), "backend", "pokemon_server.py");
            ProcessStartInfo start;
            if (File.Exists(packaged))
                start = new ProcessStartInfo(packaged);
            else if (File.Exists(script))
                start = new ProcessStartInfo("python", $"\"{script}\"");
            else
                throw new FileNotFoundException("Servidor não encontrado. Execute a partir da pasta binder ou reinstale a aplicação.");

            start.WorkingDirectory = File.Exists(packaged) ? appDir : Directory.GetCurrentDirectory();
            start.UseShellExecute = false;
            start.CreateNoWindow = true;
            start.Environment["POKEMON_BINDER_INSTANCE"] = token;
            server = Process.Start(start) ?? throw new InvalidOperationException("O servidor não iniciou.");

            using var client = new HttpClient { Timeout = TimeSpan.FromSeconds(1) };
            bool ready = false;
            for (int attempt = 0; attempt < 100; attempt++)
            {
                if (server.HasExited)
                    throw new InvalidOperationException("O servidor terminou durante o arranque. A porta 8080 pode estar ocupada.");
                try
                {
                    string response = await client.GetStringAsync(Address + "/api/instance");
                    if (response.Contains(token, StringComparison.Ordinal))
                    {
                        ready = true;
                        break;
                    }
                    throw new InvalidOperationException("A porta 8080 está ocupada por outra instância. Feche-a antes de abrir o Pokémon Binder.");
                }
                catch (HttpRequestException) { }
                catch (TaskCanceledException) { }
                await Task.Delay(200);
            }
            if (!ready) throw new TimeoutException("O servidor não ficou disponível. Verifique os logs em %APPDATA%\\PokemonBinder.");

            string userData = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "PokemonBinder", "WebView2");
            Directory.CreateDirectory(userData);
            var environment = await CoreWebView2Environment.CreateAsync(userDataFolder: userData);
            await browser.EnsureCoreWebView2Async(environment);
            browser.CoreWebView2.NavigationStarting += (_, e) =>
            {
                if (!Uri.TryCreate(e.Uri, UriKind.Absolute, out var uri) || uri.GetLeftPart(UriPartial.Authority) != Address)
                    e.Cancel = true;
            };
            browser.Source = new Uri(Address);
        }
        catch (Exception ex)
        {
            StopServer();
            MessageBox.Show(this, ex.Message, "Pokémon Binder", MessageBoxButtons.OK, MessageBoxIcon.Error);
            Close();
        }
    }

    private void StopServer()
    {
        if (server == null) return;
        try
        {
            if (!server.HasExited) server.Kill(entireProcessTree: true);
        }
        catch (InvalidOperationException) { }
        finally { server.Dispose(); server = null; }
    }
}
