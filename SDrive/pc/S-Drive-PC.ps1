# S Drive - PC launcher (Windows PowerShell 5.1+)
#
#   S-Drive-PC.bat     -> runs this script hidden (no window): opens the S Drive web page in your browser,
#                         then keeps running quietly as the "PC receiver": files the watch sends to the PC
#                         (and files you click to download on the page) are saved to Downloads\S-Drive.
#                         It exits when you press Stop on the page, or when the watch is gone for 20 minutes.
#                         Wireless debugging is NOT needed. Only if the watch happens to be connected over ADB,
#                         it also applies S Drive's one-time setup (all-files access, never sleep, speed-compile).
#   S-Drive-Tools.bat  -> menu: pair / connect the watch, setup now, install APK, shell, watch info.

param([string]$Adb = "", [switch]$Menu, [string]$SaveTo = "")

$Pkg          = "com.sdrive.app"
$Here         = Split-Path -Parent $MyInvocation.MyCommand.Path
$CfgFile      = Join-Path $Here "sdrive-adb-path.txt"
$LastFile     = Join-Path $Here "sdrive-last-connect.txt"
$WebFile      = Join-Path $Here "sdrive-last-web.txt"
$CompiledFile = Join-Path $Here "sdrive-compiled.txt"
$script:Serial = ""
$script:AdbExe = ""
[System.Net.ServicePointManager]::Expect100Continue = $false

function Read-Text([string]$path) {
    if (Test-Path $path) { return ((Get-Content $path -Raw) + "").Trim() }
    return ""
}

# ---------------- adb helpers ----------------

function Find-AdbQuiet {
    if ($Adb -and (Test-Path $Adb)) { return (Resolve-Path $Adb).Path }
    $p = Read-Text $CfgFile
    if ($p -and (Test-Path $p)) { return $p }
    $cands = @()
    $cmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($cmd) { $cands += $cmd.Source }
    $local = ""
    if ($env:LOCALAPPDATA) { $local = Join-Path $env:LOCALAPPDATA "Android\Sdk" }
    foreach ($root in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, $local, "D:\AS_SDK", "C:\Android\Sdk")) {
        if ($root) { $cands += [System.IO.Path]::Combine($root, "platform-tools", "adb.exe") }
    }
    $cands += [System.IO.Path]::Combine($Here, "adb.exe")
    foreach ($c in $cands) { if ($c -and (Test-Path -LiteralPath $c -ErrorAction SilentlyContinue)) { return $c } }
    return ""
}

function Find-AdbAsk {
    $p = Find-AdbQuiet
    if ($p) { return $p }
    while ($true) {
        $p = (Read-Host "Path to adb.exe (e.g. D:\AS_SDK\platform-tools\adb.exe)").Trim().Trim('"')
        if ($p -and (Test-Path $p)) { Set-Content -Path $CfgFile -Value $p; return $p }
        Write-Host "Not found, try again." -ForegroundColor Red
    }
}

function Get-Devices {
    $out = & $script:AdbExe devices 2>$null
    $list = @()
    foreach ($l in ($out | Select-Object -Skip 1)) {
        $parts = ($l.Trim() -split "\s+")
        if ($parts.Count -ge 2 -and $parts[1] -eq "device") { $list += $parts[0] }
    }
    return $list
}

# The connected watch (prefers the IP:port connection), or "" - never asks.
function Get-WatchSerial {
    $devs = @(Get-Devices)
    $ipDevs = @($devs | Where-Object { $_ -match "^\d+\.\d+\.\d+\.\d+:\d+$" })
    foreach ($d in ($ipDevs + $devs)) {
        $c = & $script:AdbExe -s $d shell getprop ro.build.characteristics 2>$null
        if ("$c" -match "watch") { return $d }
    }
    return ""
}

# Menu version: picks the watch, asks only if unsure.
function Select-Device {
    $devs = @(Get-Devices)
    if ($devs.Count -eq 0) { Write-Host "No device connected. Use [1] Pair and [2] Connect first." -ForegroundColor Red; return $false }
    if ($script:Serial -and ($devs -contains $script:Serial)) { return $true }
    $w = Get-WatchSerial
    if ($w) { $script:Serial = $w; return $true }
    if ($devs.Count -eq 1) { $script:Serial = $devs[0]; return $true }
    for ($i = 0; $i -lt $devs.Count; $i++) { Write-Host ("  [{0}] {1}" -f ($i + 1), $devs[$i]) }
    $n = Read-Host "Choose"
    $idx = 0
    if ([int]::TryParse($n, [ref]$idx) -and $idx -ge 1 -and $idx -le $devs.Count) { $script:Serial = $devs[$idx - 1]; return $true }
    return $false
}

# Plain function (no param block) so flags like -r are passed straight to adb.
function Invoke-Adb {
    & $script:AdbExe -s $script:Serial @args
}

function Get-WatchIp {
    $txt = (Invoke-Adb shell "ip -f inet addr show wlan0" 2>$null) -join "`n"
    $m = [regex]::Match($txt, "inet (\d+\.\d+\.\d+\.\d+)")
    if ($m.Success) { return $m.Groups[1].Value }
    if ($script:Serial -match "^(\d+\.\d+\.\d+\.\d+):") { return $Matches[1] }
    return ""
}

# All-files access, notifications, never sleep (instant, only when missing) + speed-compile once per install.
function Auto-Setup([bool]$force) {
    $path = (Invoke-Adb shell "pm path $Pkg" 2>$null) -join ""
    if ($path -notmatch "package:") { Write-Host "S Drive is not installed on the watch yet." -ForegroundColor Red; return }
    $ops = (Invoke-Adb shell "appops get --uid $Pkg MANAGE_EXTERNAL_STORAGE" 2>$null) -join " "
    if ($force -or $ops -notmatch "allow") {
        Invoke-Adb shell "appops set --uid $Pkg MANAGE_EXTERNAL_STORAGE allow" 2>$null | Out-Null
        Write-Host "  all-files access: on"
    }
    Invoke-Adb shell "pm grant $Pkg android.permission.POST_NOTIFICATIONS" 2>$null | Out-Null
    $wl = (Invoke-Adb shell "dumpsys deviceidle whitelist" 2>$null) -join " "
    if ($force -or $wl -notmatch [regex]::Escape($Pkg)) {
        Invoke-Adb shell "dumpsys deviceidle whitelist +$Pkg" 2>$null | Out-Null
        Write-Host "  never sleep: on"
    }
    $upd = ((Invoke-Adb shell "dumpsys package $Pkg | grep -m 1 lastUpdateTime" 2>$null) -join "").Trim()
    if ($force -or ($upd -and $upd -ne (Read-Text $CompiledFile))) {
        Write-Host "  optimizing S Drive for a smooth UI (once after each install, about 1 minute)..."
        Invoke-Adb shell "cmd package compile -m speed -f $Pkg" 2>$null | Out-Null
        if ($upd) { Set-Content -Path $CompiledFile -Value $upd }
        Write-Host "  smooth UI: done"
    }
}

# ---------------- finding S Drive on the Wi-Fi ----------------

function Test-SDrive([string]$ip, [int]$port) {
    try {
        $r = [System.Net.HttpWebRequest]::Create("http://${ip}:$port/api/ping")
        $r.Timeout = 1200
        $resp = $r.GetResponse()
        $body = (New-Object System.IO.StreamReader($resp.GetResponseStream())).ReadToEnd()
        $resp.Close()
        return ($body -match "sdrive")
    } catch { return $false }
}

function Find-SDrive([string]$ip) {
    foreach ($port in 8080..8085) { if (Test-SDrive $ip $port) { return $port } }
    return 0
}

# Opens S Drive > Transfer on the watch over adb if it isn't receiving yet, then waits for it.
function Ensure-SDrive([string]$ip) {
    $port = Find-SDrive $ip
    if ($port -ne 0) { return $port }
    Write-Host "Opening S Drive > Transfer on the watch..."
    Invoke-Adb shell input keyevent KEYCODE_WAKEUP 2>$null | Out-Null
    Invoke-Adb shell "am start -n $Pkg/com.sdrive.wear.MainActivity --es open transfer" 2>$null | Out-Null
    for ($i = 0; $i -lt 10; $i++) {
        Start-Sleep -Seconds 2
        $port = Find-SDrive $ip
        if ($port -ne 0) { return $port }
    }
    return 0
}

# Looks for S Drive on every device of this PC's local networks (ports 8080/8081).
function Scan-Lan {
    $mine = @()
    try {
        $mine = @(Get-NetIPAddress -AddressFamily IPv4 -ErrorAction Stop |
            Where-Object { $_.IPAddress -notlike "127.*" -and $_.IPAddress -notlike "169.254.*" } |
            ForEach-Object { $_.IPAddress })
    } catch { }
    if ($mine.Count -eq 0) {
        $mine = @([System.Net.Dns]::GetHostAddresses([System.Net.Dns]::GetHostName()) |
            Where-Object { $_.AddressFamily -eq [System.Net.Sockets.AddressFamily]::InterNetwork -and $_.ToString() -notlike "127.*" } |
            ForEach-Object { $_.ToString() })
    }
    foreach ($ip in $mine) {
        $pre = $ip.Substring(0, $ip.LastIndexOf('.') + 1)
        $probes = @()
        foreach ($h in 1..254) {
            foreach ($port in 8080, 8081) {
                $c = New-Object System.Net.Sockets.TcpClient
                $probes += ,@($c, $c.ConnectAsync($pre + $h, $port), ($pre + $h), $port)
            }
        }
        Start-Sleep -Milliseconds 1200
        $open = @()
        foreach ($p in $probes) {
            if ($p[1].IsCompleted -and -not $p[1].IsFaulted -and $p[0].Connected) { $open += ,@($p[2], $p[3]) }
            try { $p[0].Close() } catch { }
        }
        foreach ($o in $open) { if (Test-SDrive $o[0] $o[1]) { return @{ Ip = $o[0]; Port = $o[1] } } }
    }
    return $null
}

# Returns @{ Ip; Port } after opening the page in the browser, or $null when S Drive wasn't found.
function Open-SDrive([bool]$withAdb) {
    $ip = ""
    $port = 0
    if ($withAdb -and $script:AdbExe) {
        if (@(Get-Devices).Count -eq 0) {
            $last = Read-Text $LastFile
            if ($last) { & $script:AdbExe connect $last 2>$null | Out-Null }
        }
        $script:Serial = Get-WatchSerial
        if ($script:Serial) {
            Write-Host "Watch connected over ADB - checking S Drive setup..."
            Auto-Setup $false
            $ip = Get-WatchIp
            if ($ip) { $port = Ensure-SDrive $ip }
        }
    }
    if ($port -eq 0) {
        $saved = Read-Text $WebFile
        if ($saved -match "^(\d+\.\d+\.\d+\.\d+):(\d+)$") {
            if (Test-SDrive $Matches[1] ([int]$Matches[2])) { $ip = $Matches[1]; $port = [int]$Matches[2] }
        }
    }
    if ($port -eq 0) {
        Write-Host "Looking for S Drive on your Wi-Fi..."
        $found = Scan-Lan
        if ($found) { $ip = $found.Ip; $port = $found.Port }
    }
    if ($port -ne 0) {
        Set-Content -Path $WebFile -Value "${ip}:$port"
        Write-Host "Opening http://${ip}:$port  (PIN is shown on the watch: S Drive > Transfer)"
        Start-Process "http://${ip}:$port"
        return @{ Ip = $ip; Port = $port }
    }
    return $null
}

function Show-WifiLink {
    $line = (Invoke-Adb shell "dumpsys wifi 2>/dev/null | grep -m 1 mWifiInfo") -join " "
    if (-not $line) { Write-Host "Couldn't read Wi-Fi info." -ForegroundColor Red; return }
    $freq = [regex]::Match($line, "Frequency: (\d+)")
    $link = [regex]::Match($line, "Link speed: (\d+)")
    $rssi = [regex]::Match($line, "RSSI: (-?\d+)")
    if ($freq.Success) {
        $f = [int]$freq.Groups[1].Value
        $band = "5 GHz"
        if ($f -lt 3000) { $band = "2.4 GHz" }
        Write-Host "Band: $band ($f MHz)"
    }
    if ($link.Success) {
        $l = [int]$link.Groups[1].Value
        Write-Host ("Link speed: {0} Mbps  (real file transfer max is roughly {1:N1} MB/s)" -f $l, ($l / 8 * 0.5))
    }
    if ($rssi.Success) {
        $r = [int]$rssi.Groups[1].Value
        $q = "good"
        if ($r -lt -67) { $q = "weak - move closer to the router" }
        if ($r -lt -75) { $q = "very weak - move closer to the router" }
        Write-Host "Signal: $r dBm ($q)"
    }
}

# Works in the hidden window: a Retry/Cancel box (console prompt if Windows Forms is unavailable).
function Ask-Retry {
    $msg = "S Drive was not found on your Wi-Fi.`n`nOn the watch open S Drive > Transfer (Transfer: ON, same Wi-Fi as this PC), then click Retry."
    try {
        Add-Type -AssemblyName System.Windows.Forms -ErrorAction Stop
        $r = [System.Windows.Forms.MessageBox]::Show($msg, "S Drive", [System.Windows.Forms.MessageBoxButtons]::RetryCancel, [System.Windows.Forms.MessageBoxIcon]::Information)
        return ($r -eq [System.Windows.Forms.DialogResult]::Retry)
    } catch {
        Write-Host $msg -ForegroundColor Red
        $a = Read-Host "Press Enter to try again, or type q to quit"
        return ($a -ne "q")
    }
}

# ---------------- PC receiver: saves into Downloads\S-Drive ----------------

function Get-SaveDir {
    if ($SaveTo) { return $SaveTo }
    $d = ""
    try { $d = (New-Object -ComObject Shell.Application).NameSpace("shell:Downloads").Self.Path } catch { }
    if (-not $d) {
        $h = $env:USERPROFILE
        if (-not $h) { $h = $HOME }
        $d = Join-Path $h "Downloads"
    }
    return (Join-Path $d "S-Drive")
}

function Get-WebError($err) {
    $ex = $err.Exception
    while ($ex -and -not ($ex -is [System.Net.WebException])) { $ex = $ex.InnerException }
    return $ex
}

# $null = watch unreachable; otherwise the outbox (items, stop, on). No PIN needed: the watch trusts this PC
# once the PIN was entered on the page.
function Get-Outbox([string]$ip, [int]$port) {
    try {
        $r = [System.Net.HttpWebRequest]::Create("http://${ip}:$port/api/outbox")
        $r.Timeout = 5000
        $r.Headers.Add("X-SD-Agent", "pc")
        $resp = $r.GetResponse()
        $txt = (New-Object System.IO.StreamReader($resp.GetResponseStream(), [System.Text.Encoding]::UTF8)).ReadToEnd()
        $resp.Close()
        return ($txt | ConvertFrom-Json)
    } catch {
        $we = Get-WebError $_
        if ($we -and $we.Response) { $we.Response.Close(); return @{ items = @(); denied = $true } }
        return $null
    }
}

function Clean-Name([string]$n) {
    foreach ($c in [System.IO.Path]::GetInvalidFileNameChars()) { $n = $n.Replace([string]$c, "_") }
    $n = $n.Trim()
    if (-not $n) { $n = "file" }
    return $n
}

function Unique-Path([string]$dir, [string]$name) {
    $p = Join-Path $dir $name
    if (-not (Test-Path -LiteralPath $p)) { return $p }
    $base = [System.IO.Path]::GetFileNameWithoutExtension($name)
    $ext = [System.IO.Path]::GetExtension($name)
    $i = 1
    while (Test-Path -LiteralPath $p) { $p = Join-Path $dir ("{0} ({1}){2}" -f $base, $i, $ext); $i++ }
    return $p
}

function Save-OutboxItem([string]$ip, [int]$port, $item, [string]$dir) {
    $dest = Unique-Path $dir (Clean-Name ([string]$item.name))
    $part = "$dest.sdpart"
    try {
        $r = [System.Net.HttpWebRequest]::Create("http://${ip}:$port/api/outbox/file?id=$($item.id)")
        $r.Headers.Add("X-SD-Agent", "pc")
        $r.Timeout = 15000
        $r.ReadWriteTimeout = 120000
        $resp = $r.GetResponse()
        $expect = $resp.ContentLength
        $in = $resp.GetResponseStream()
        $out = [System.IO.File]::Create($part)
        try { $in.CopyTo($out, 1MB) } finally { $out.Close(); $in.Close(); $resp.Close() }
        if ($expect -ge 0 -and (Get-Item -LiteralPath $part).Length -ne $expect) { throw "incomplete" }
        Move-Item -LiteralPath $part -Destination $dest
        return $dest
    } catch {
        Remove-Item -LiteralPath $part -ErrorAction SilentlyContinue
        return $null
    }
}

function Run-Receiver([string]$ip, [int]$port) {
    $dir = Get-SaveDir
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    $tries = @{}
    $lastOk = Get-Date
    $misses = 0
    while ($true) {
        $j = Get-Outbox $ip $port
        if ($null -eq $j) {
            $misses++
            if ($misses % 5 -eq 0) { $p = Find-SDrive $ip; if ($p -ne 0) { $port = $p } }
            if (((Get-Date) - $lastOk).TotalMinutes -ge 20) { break }
        } else {
            $misses = 0
            $lastOk = Get-Date
            if ($j.stop) { break }
            foreach ($it in @($j.items)) {
                if ($null -eq $it) { continue }
                $k = "$($it.id)"
                if ([int]$tries[$k] -ge 3) { continue }
                if (-not (Save-OutboxItem $ip $port $it $dir)) { $tries[$k] = 1 + [int]$tries[$k] }
            }
        }
        Start-Sleep -Seconds 2
    }
}

function Pick-Files([string]$title, [string]$filter) {
    Add-Type -AssemblyName System.Windows.Forms
    $dlg = New-Object System.Windows.Forms.OpenFileDialog
    $dlg.Multiselect = $true
    $dlg.Title = $title
    if ($filter) { $dlg.Filter = $filter }
    if ($dlg.ShowDialog() -eq [System.Windows.Forms.DialogResult]::OK) { return $dlg.FileNames }
    return @()
}

# ======================= default: open the web page =======================

if (-not $Menu) {
    Write-Host ""
    Write-Host "  S DRIVE" -ForegroundColor White
    Write-Host ""
    # One PC receiver at a time; a second run only opens the page.
    $owner = $true
    try {
        $mutex = New-Object System.Threading.Mutex($false, "SDrivePCReceiver")
        $owner = $mutex.WaitOne(0)
    } catch [System.Threading.AbandonedMutexException] { $owner = $true } catch { $owner = $true }
    $script:AdbExe = Find-AdbQuiet
    $found = $null
    while ($true) {
        $found = Open-SDrive $true
        if ($found) { break }
        if (-not (Ask-Retry)) { exit }
    }
    if ($owner) { Run-Receiver $found.Ip ([int]$found.Port) }
    exit
}

# ======================= -Menu: tools =======================

$script:AdbExe = Find-AdbAsk
Write-Host "Using adb: $($script:AdbExe)" -ForegroundColor DarkGray

function Show-Menu {
    Write-Host ""
    Write-Host "  S DRIVE  -  tools" -ForegroundColor White
    Write-Host "  -------------------------------------------" -ForegroundColor DarkGray
    Write-Host "  [1]  Pair watch       (Wireless debugging > Pair new device)"
    Write-Host "  [2]  Connect watch    (IP:port at top of Wireless debugging)"
    Write-Host "  [3]  Show devices"
    Write-Host "  [4]  Set up S Drive now (all files, notifications, never sleep, smooth UI)"
    Write-Host "  [5]  Install APK on watch"
    Write-Host "  [6]  Open watch shell"
    Write-Host "  [7]  Watch info + Wi-Fi link (band, speed, signal)"
    Write-Host "  [8]  Open S Drive in the browser"
    Write-Host "  [0]  Exit"
}

while ($true) {
    Show-Menu
    $c = Read-Host "Choose"
    switch ($c) {
        "1" {
            $hp = (Read-Host "IP:port shown under 'Pair new device'").Trim()
            $code = (Read-Host "6-digit pairing code").Trim()
            & $script:AdbExe pair $hp $code
            Write-Host "Now use [2] with the IP:port shown at the TOP of the Wireless debugging screen."
        }
        "2" {
            $last = Read-Text $LastFile
            $prompt = "Watch IP:port from Wireless debugging (e.g. 192.168.1.42:41803)"
            if ($last) { $prompt = "$prompt [Enter = $last]" }
            $hp = (Read-Host $prompt).Trim()
            if (-not $hp) { $hp = $last }
            if ($hp -match "^\d+$") {
                Write-Host "Type the full IP:port, e.g. 192.168.1.42:$hp" -ForegroundColor Red
            } elseif ($hp) {
                & $script:AdbExe connect $hp
                Set-Content -Path $LastFile -Value $hp
                $script:Serial = ""
                if (Select-Device) { Auto-Setup $false }
            }
        }
        "3" { & $script:AdbExe devices -l }
        "4" { if (Select-Device) { Auto-Setup $true; Write-Host "Done. Re-open S Drive on the watch." } }
        "5" {
            if (Select-Device) {
                $apks = @(Pick-Files "APK to install on the watch" "Android app (*.apk)|*.apk")
                foreach ($a in $apks) { Write-Host "Installing $a"; Invoke-Adb install -r "$a" }
            }
        }
        "6" {
            if (Select-Device) {
                Write-Host "Type 'exit' to leave the watch shell." -ForegroundColor DarkGray
                Invoke-Adb shell
            }
        }
        "7" {
            if (Select-Device) {
                Invoke-Adb shell "getprop ro.product.model; getprop ro.build.version.release; df -h /sdcard; dumpsys battery | grep level"
                Show-WifiLink
            }
        }
        "8" { if (-not (Open-SDrive $true)) { Write-Host "S Drive not found - open S Drive > Transfer on the watch, then try again." -ForegroundColor Red } }
        "0" { break }
        default { Write-Host "?" }
    }
    if ($c -eq "0") { break }
}
