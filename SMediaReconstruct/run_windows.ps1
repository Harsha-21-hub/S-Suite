<#
    S-MediaReconstruct — run from source (no EXE build)
    Sets up .venv, ensures FFmpeg is extracted (reusing the cache), then launches
    the app directly with:  python src\app.py
#>
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $root

function Say($m){ Write-Host "==> $m" -ForegroundColor Cyan }

if(-not (Get-Command python -ErrorAction SilentlyContinue)){ throw "Python 3.10+ not on PATH." }

$venv = Join-Path $root ".venv"
if(-not (Test-Path (Join-Path $venv "Scripts\python.exe"))){ Say "Creating .venv"; python -m venv $venv }
$vpy = Join-Path $venv "Scripts\python.exe"

Say "Installing runtime deps (pywebview, pythonnet)"
& $vpy -m pip install --upgrade pip | Out-Null
& $vpy -m pip install "pywebview>=5.0" "pythonnet>=3.0.3"

# Ensure FFmpeg is available in src\ffmpeg\bin (reuse cache).
$ffBin = Join-Path $root "src\ffmpeg\bin"
if(-not (Test-Path (Join-Path $ffBin "ffmpeg.exe"))){
    New-Item -ItemType Directory -Force -Path $ffBin | Out-Null
    $zip = Join-Path $root ".build_cache\ffmpeg-release-essentials.zip"
    if(-not (Test-Path $zip)){
        New-Item -ItemType Directory -Force -Path (Split-Path $zip) | Out-Null
        Say "Downloading FFmpeg"
        Invoke-WebRequest "https://www.gyan.dev/ffmpeg/builds/ffmpeg-release-essentials.zip" -OutFile $zip -UseBasicParsing
    } else { Say "Reusing cached FFmpeg" }
    $tmp = Join-Path $root ".build_cache\ff_extract"
    if(Test-Path $tmp){ Remove-Item $tmp -Recurse -Force }
    Expand-Archive $zip $tmp -Force
    Get-ChildItem $tmp -Recurse -Include ffmpeg.exe,ffprobe.exe,ffplay.exe | ForEach-Object { Copy-Item $_.FullName $ffBin -Force }
    Remove-Item $tmp -Recurse -Force
}

Say "Launching S-MediaReconstruct from source"
& $vpy (Join-Path $root "src\app.py")
