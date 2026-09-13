$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $root

Write-Host ""
Write-Host "============================================================"
Write-Host "S-MediaReconstruct BUILD"
Write-Host "============================================================"
Write-Host ""

# 1. Python
$py = Get-Command py -ErrorAction SilentlyContinue
if ($null -eq $py) {
    $py = Get-Command python -ErrorAction SilentlyContinue
}
if ($null -eq $py) {
    throw "Python was not found."
}
$python = $py.Source
& $python --version

# 2. Virtual environment
$venv = Join-Path $root ".venv"
$venvPython = Join-Path $venv "Scripts\python.exe"

if (-not (Test-Path $venvPython)) {
    Write-Host ""
    Write-Host "Creating .venv..."
    & $python -m venv $venv
    if ($LASTEXITCODE -ne 0) {
        throw "Could not create .venv."
    }
}

# 3. Python dependencies
$requirements = Join-Path $root "requirements.txt"
if (-not (Test-Path $requirements)) {
    throw "requirements.txt was not found."
}

Write-Host ""
Write-Host "Installing Python dependencies..."
& $venvPython -m pip install --upgrade pip
if ($LASTEXITCODE -ne 0) {
    throw "pip upgrade failed."
}

& $venvPython -m pip install --upgrade -r $requirements
if ($LASTEXITCODE -ne 0) {
    throw "Python dependency installation failed."
}

# 4. FFmpeg locations
$ffDir = Join-Path $root "src\ffmpeg\bin"
$ffmpeg = Join-Path $ffDir "ffmpeg.exe"
$ffprobe = Join-Path $ffDir "ffprobe.exe"
$ffplay = Join-Path $ffDir "ffplay.exe"

New-Item -ItemType Directory -Force -Path $ffDir | Out-Null

# Check current project first.
if (-not ((Test-Path $ffmpeg) -and (Test-Path $ffprobe))) {

    # Check PATH.
    $pathFfmpeg = Get-Command ffmpeg.exe -ErrorAction SilentlyContinue
    $pathFfprobe = Get-Command ffprobe.exe -ErrorAction SilentlyContinue
    $pathFfplay = Get-Command ffplay.exe -ErrorAction SilentlyContinue

    if (($null -ne $pathFfmpeg) -and ($null -ne $pathFfprobe)) {
        Write-Host ""
        Write-Host "Using FFmpeg already installed on PATH."
        Copy-Item $pathFfmpeg.Source $ffmpeg -Force
        Copy-Item $pathFfprobe.Source $ffprobe -Force
        if ($null -ne $pathFfplay) {
            Copy-Item $pathFfplay.Source $ffplay -Force
        }
    }
}

# If still missing, install current Gyan FFmpeg Essentials via WinGet.
if (-not ((Test-Path $ffmpeg) -and (Test-Path $ffprobe))) {

    $winget = Get-Command winget.exe -ErrorAction SilentlyContinue

    if ($null -eq $winget) {
        throw "FFmpeg/FFprobe are missing and WinGet is unavailable."
    }

    Write-Host ""
    Write-Host "FFmpeg not found. Installing Gyan FFmpeg Essentials..."
    Write-Host ""

    & winget install --id Gyan.FFmpeg.Essentials --exact --silent `
        --accept-source-agreements --accept-package-agreements

    if ($LASTEXITCODE -ne 0) {
        throw "WinGet could not install Gyan.FFmpeg.Essentials."
    }

    # Refresh environment PATH.
    $machinePath = [Environment]::GetEnvironmentVariable("Path", "Machine")
    $userPath = [Environment]::GetEnvironmentVariable("Path", "User")
    if ($machinePath -and $userPath) {
        $env:Path = "$machinePath;$userPath"
    }

    $pathFfmpeg = Get-Command ffmpeg.exe -ErrorAction SilentlyContinue
    $pathFfprobe = Get-Command ffprobe.exe -ErrorAction SilentlyContinue
    $pathFfplay = Get-Command ffplay.exe -ErrorAction SilentlyContinue

    if (($null -ne $pathFfmpeg) -and ($null -ne $pathFfprobe)) {
        Copy-Item $pathFfmpeg.Source $ffmpeg -Force
        Copy-Item $pathFfprobe.Source $ffprobe -Force
        if ($null -ne $pathFfplay) {
            Copy-Item $pathFfplay.Source $ffplay -Force
        }
    }
}

# WinGet sometimes does not refresh PATH until a new shell.
if (-not ((Test-Path $ffmpeg) -and (Test-Path $ffprobe))) {

    $packages = Join-Path $env:LOCALAPPDATA "Microsoft\WinGet\Packages"

    if (Test-Path $packages) {

        Write-Host ""
        Write-Host "Searching WinGet package directory..."

        $foundFfmpeg = Get-ChildItem $packages -Filter "ffmpeg.exe" -Recurse `
            -ErrorAction SilentlyContinue | Select-Object -First 1

        $foundFfprobe = Get-ChildItem $packages -Filter "ffprobe.exe" -Recurse `
            -ErrorAction SilentlyContinue | Select-Object -First 1

        $foundFfplay = Get-ChildItem $packages -Filter "ffplay.exe" -Recurse `
            -ErrorAction SilentlyContinue | Select-Object -First 1

        if (($null -ne $foundFfmpeg) -and ($null -ne $foundFfprobe)) {
            Copy-Item $foundFfmpeg.FullName $ffmpeg -Force
            Copy-Item $foundFfprobe.FullName $ffprobe -Force
            if ($null -ne $foundFfplay) {
                Copy-Item $foundFfplay.FullName $ffplay -Force
            }
        }
    }
}

# Final FFmpeg verification.
if (-not (Test-Path $ffmpeg)) {
    throw "FFmpeg could not be located after automatic installation."
}
if (-not (Test-Path $ffprobe)) {
    throw "FFprobe could not be located after automatic installation."
}

Write-Host ""
Write-Host "Verifying FFmpeg..."
$ffmpegText = & $ffmpeg "-version" 2>&1
$ffmpegExit = $LASTEXITCODE
if ($ffmpegExit -ne 0) {
    throw "FFmpeg verification failed. Exit code: $ffmpegExit"
}
$ffmpegText | Select-Object -First 2 | ForEach-Object { Write-Host $_ }

Write-Host ""
Write-Host "Verifying FFprobe..."
$ffprobeText = & $ffprobe "-version" 2>&1
$ffprobeExit = $LASTEXITCODE
if ($ffprobeExit -ne 0) {
    throw "FFprobe verification failed. Exit code: $ffprobeExit"
}
$ffprobeText | Select-Object -First 2 | ForEach-Object { Write-Host $_ }

# 5. Source checks.
$app = Join-Path $root "src\app.py"
$ui = Join-Path $root "src\ui"
$icon = Join-Path $root "src\icons\S-MediaReconstruct.ico"

if (-not (Test-Path $app)) { throw "Missing src\app.py." }
if (-not (Test-Path $ui)) { throw "Missing src\ui." }
if (-not (Test-Path $icon)) { throw "Missing src\icons\S-MediaReconstruct.ico." }

# 6. Clean previous PyInstaller output.
Remove-Item (Join-Path $root "build") -Recurse -Force -ErrorAction SilentlyContinue
Remove-Item (Join-Path $root "dist") -Recurse -Force -ErrorAction SilentlyContinue
Remove-Item (Join-Path $root "S-MediaReconstruct.spec") -Force -ErrorAction SilentlyContinue

# 7. PyInstaller arguments.
$argsList = @(
    "--clean",
    "--noconfirm",
    "--onefile",
    "--windowed",
    "--name", "S-MediaReconstruct",
    "--icon", ".\src\icons\S-MediaReconstruct.ico",
    "--add-binary", ".\src\ffmpeg\bin\ffmpeg.exe;ffmpeg\bin",
    "--add-binary", ".\src\ffmpeg\bin\ffprobe.exe;ffmpeg\bin",
    "--add-data", ".\src\ui;smr_ui"
)

if (Test-Path $ffplay) {
    $argsList += @("--add-binary", ".\src\ffmpeg\bin\ffplay.exe;ffmpeg\bin")
}

$fonts = Join-Path $root "src\fonts"
if (Test-Path $fonts) {
    $argsList += @("--add-data", ".\src\fonts;fonts")
}

$argsList += $app

# 8. Build.
Write-Host ""
Write-Host "============================================================"
Write-Host "BUILDING SELF-CONTAINED EXE"
Write-Host "============================================================"
Write-Host ""

& $venvPython -m PyInstaller @argsList

if ($LASTEXITCODE -ne 0) {
    throw "PyInstaller failed."
}

# 9. Verify output.
$exe = Join-Path $root "dist\S-MediaReconstruct.exe"

if (-not (Test-Path $exe)) {
    throw "S-MediaReconstruct.exe was not created."
}

$size = (Get-Item $exe).Length

Write-Host ""
Write-Host "============================================================" -ForegroundColor Green
Write-Host "BUILD SUCCESSFUL" -ForegroundColor Green
Write-Host "============================================================" -ForegroundColor Green
Write-Host ""
Write-Host "EXE:"
Write-Host $exe -ForegroundColor Yellow
Write-Host ""
Write-Host "Size: $([Math]::Round($size / 1MB, 1)) MB"
Write-Host ""
Write-Host "Python + FFmpeg + FFprobe are embedded."
Write-Host ""
