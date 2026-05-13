$process = New-Object System.Diagnostics.Process
$process.StartInfo.FileName = "e:\mywork\chouchou-music\android-sdk\cmdline-tools\latest\bin\sdkmanager.bat"
$process.StartInfo.Arguments = "platforms;android-34 build-tools;34.0.0 --sdk_root=e:\mywork\chouchou-music\android-sdk"
$process.StartInfo.RedirectStandardInput = $true
$process.StartInfo.RedirectStandardOutput = $true
$process.StartInfo.UseShellExecute = $false
$process.StartInfo.CreateNoWindow = $false

$process.Start() | Out-Null

Start-Sleep -Milliseconds 5000

$process.StandardInput.WriteLine("y")
$process.StandardInput.Flush()

Start-Sleep -Milliseconds 5000

$process.StandardInput.WriteLine("y")
$process.StandardInput.Flush()

$process.WaitForExit()
Write-Host "Exit code: $($process.ExitCode)"