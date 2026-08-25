# ============================================================
# OneKeyInstall.ps1 — Windows 一键安装 PL-server-plugins
# 会做的事（与 OneKeyInstall.sh 一致）：
#   1. 检测/安装 git（winget 装 Git for Windows）
#   2. 下载便携 JDK26 到 .\pls\jdk（Oracle 官方直链，免安装）
#   3. 克隆本仓库到当前目录\PL-server-plugins
#   4. 从 MindustxX 最新发行版下载 server 文件，改名 server.jar
#   5. 下载 Mindust 源码到当前目录\mindustrySourceDir
#   6. 把 config.conf 的 mindustrySourceDir 指向它
# 之后运行 PL-server-plugins\run.bat 即可开服。
#
# 幂等：中途 Ctrl-C、断网、再次运行都不会搞坏，已完成步骤自动跳过。
# 用法: irm https://raw.githubusercontent.com/power-line-server/PL-server-plugins/main/OneKeyInstall.ps1 | iex
# ============================================================
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$Repo = 'power-line-server/PL-server-plugins'
$MindustryX = 'TinyLake/MindustryX'
$Base = (Get-Location).Path
$PlHome = Join-Path $Base 'PL-server-plugins'
$MindustryDir = Join-Path $Base 'mindustrySourceDir'
$PlsDir = Join-Path $Base '.pls'
$JdkDir = Join-Path $PlsDir 'jdk'
$Force = $args -contains '--force'

$Mirrors = @(
  'https://github.com'
  'https://ghfast.top/https://github.com'
  'https://gh-proxy.com/https://github.com'
  'https://ghproxy.net/https://github.com'
  'https://mirror.ghproxy.com/https://github.com'
  'https://ghproxy.cc/https://github.com'
)
$OracleJdk = 'https://download.oracle.com/java/26/latest'

function Log  { Write-Host "[PLS] $args" -ForegroundColor Cyan }
function Die  { Write-Host "[PLS] $args" -ForegroundColor Red; exit 1 }

# 测速：返回最快的 URL（下载前 1MB 计时）
function Pick-Fastest([string[]]$Urls) {
  $best = $null; $bestS = 0.0
  foreach ($u in $Urls) {
    try {
      $sw = [System.Diagnostics.Stopwatch]::StartNew()
      $req = [System.Net.HttpWebRequest]::Create($u)
      $req.AddRange(0, 1048575); $req.Timeout = 10000
      $resp = $req.GetResponse()
      $s = $resp.GetResponseStream()
      $buf = New-Object byte[] 65536
      $read = 0
      while ($read -lt 1048576) {
        $n = $s.Read($buf, 0, 65536); if ($n -le 0) { break }
        $read += $n
      }
      $resp.Close(); $sw.Stop()
      $speed = $read / $sw.Elapsed.TotalSeconds
      Log "  测速 $([math]::Round($speed))B/s  $u"
      if ($speed -gt $bestS) { $bestS = $speed; $best = $u }
    } catch { Log "  测速失败: $u" }
  }
  if (-not $best) { return $Urls[0] }
  return $best
}

# 带重试下载
function Download([string]$Url, [string]$Out, [int]$Tries = 3) {
  for ($i = 1; $i -le $Tries; $i++) {
    Log "  下载中($i/$Tries): $Url"
    try {
      Invoke-WebRequest -Uri $Url -OutFile "$Out.part" -UseBasicParsing -TimeoutSec 900
      Move-Item -Force "$Out.part" $Out
      return
    } catch {
      Remove-Item "$Out.part" -ErrorAction SilentlyContinue
      Start-Sleep -Seconds 3
    }
  }
  throw "下载失败: $Url"
}

function Has-Java26 {
  $j = Join-Path $JdkDir 'bin\java.exe'
  if (-not (Test-Path $j)) { return $false }
  $v = & $j -version 2>&1 | Out-String
  return $v -match '"26\.'
}

New-Item -ItemType Directory -Force -Path $PlsDir | Out-Null

# ---------- 1. git ----------
if (Get-Command git -ErrorAction SilentlyContinue) {
  Log "git 已安装: $(git --version)"
} else {
  Log '安装 git ...'
  if (Get-Command winget -ErrorAction SilentlyContinue) {
    winget install --id Git.Git -e --silent --accept-package-agreements --accept-source-agreements
    if ($LASTEXITCODE -ne 0) { Die 'winget 安装 git 失败，请手动安装 Git for Windows 后重跑' }
    $env:Path = [Environment]::GetEnvironmentVariable('Path', 'Machine') + ';' + [Environment]::GetEnvironmentVariable('Path', 'User')
    if (-not (Get-Command git -ErrorAction SilentlyContinue)) { Die 'git 装好了但 PATH 没刷新，请重开终端再运行' }
  } else {
    Die '没找到 winget。请手动安装 git：https://git-scm.com/download/win'
  }
}

# ---------- 2. JDK26 便携 ----------
if (-not (Has-Java26) -or $Force) {
  Log "下载便携 JDK26 到 $JdkDir ..."
  if (Test-Path $JdkDir) { Remove-Item -Recurse -Force $JdkDir }
  $arch = if ($env:PROCESSOR_ARCHITECTURE -match 'ARM') { 'aarch64' } else { 'x64' }
  $prod = if ($env:PROCESSOR_ARCHITECTURE -match 'ARM') { 'aarch64' } else { 'x64' }
  $jUrl = "$OracleJdk/jdk-26_windows-$prod.zip"
  Download $jUrl (Join-Path $PlsDir 'jdk.zip')
  Log '  解压 JDK ...'
  Expand-Archive -Force (Join-Path $PlsDir 'jdk.zip') $PlsDir
  Remove-Item (Join-Path $PlsDir 'jdk.zip')
  # 解压出来是 jdk-26.x.y 一层目录，剥掉
  $inner = Get-ChildItem $PlsDir -Directory | Where-Object { $_.Name -like 'jdk-26*' } | Select-Object -First 1
  if (-not $inner) { Die 'JDK 解压结构不对，请手动解压 JDK26 到 .\pls\jdk 后重跑' }
  Move-Item $inner.FullName $JdkDir
} else {
  Log "JDK26 已就绪: $JdkDir"
}
$java = Join-Path $JdkDir 'bin\java.exe'
if (-not (Test-Path $java)) { Die "JDK 不可用: $java" }

# ---------- 3. 仓库 ----------
$cfgPath = Join-Path $PlHome 'config\scripts\data\config.conf'
if (-not (Test-Path $cfgPath)) {
  Log "克隆仓库到 $PlHome ..."
  $ok = $false
  foreach ($m in $Mirrors) {
    $u = "$($m.TrimEnd('/'))/$Repo.git"
    Log "  尝试: $u"
    & git clone --depth 1 $u $PlHome 2>$null
    if ($LASTEXITCODE -eq 0) { $ok = $true; break }
    if (Test-Path $PlHome) { Remove-Item -Recurse -Force $PlHome }
  }
  if (-not $ok) {
    & git clone --depth 1 "https://github.com/$Repo.git" $PlHome
    $ok = $LASTEXITCODE -eq 0
  }
  if (-not $ok) { Die '仓库克隆失败，请检查网络后重跑' }
} else {
  Log "仓库已存在: $PlHome"
}
Set-Location $PlHome

# ---------- 4. server.jar ----------
$jarPath = Join-Path $PlHome 'server.jar'
if (-not (Test-Path $jarPath) -or $Force) {
  Log '获取 MindustxX 最新发行版信息 ...'
  $json = $null
  foreach ($m in $Mirrors) {
    try {
      $json = Invoke-RestMethod -Uri "$($m.TrimEnd('/'))/repos/$MindustryX/releases/latest" -TimeoutSec 30
      if ($json.assets.Count -gt 0) { break }
    } catch { $json = $null }
  }
  if (-not $json -or $json.assets.Count -eq 0) { Die '拿不到 MindustxX 版本信息，请检查网络后重跑' }
  $asset = $json.assets | Where-Object { $_.name -match 'server' } | Select-Object -First 1
  if (-not $asset) { $asset = $json.assets | Where-Object { $_.name -like '*.jar' } | Select-Object -First 1 }
  if (-not $asset) { Die '发行版里没找到 server 文件' }
  Log "找到资产: $($asset.name)"
  # 下载 URL 直接来自 API（已是镜像响应里的 URL，若还是 github.com 开头则套镜像）
  $dl = $asset.browser_download_url
  $cands = @($dl)
  if ($dl -like 'https://github.com*') {
    $cands = @($Mirrors | ForEach-Object { "$($_.TrimEnd('/'))/$($dl.Substring('https://github.com/'.Length))" })
  }
  $fast = Pick-Fastest $cands
  Download $fast $jarPath
} else {
  Log 'server.jar 已存在'
}

# ---------- 5. 游戏源码 ----------
$assetsDir = Join-Path $MindustryDir 'core\assets'
if (-not (Test-Path $assetsDir) -or $Force) {
  Log "下载 Mindust 源码到 $MindustryDir（首次约 100MB+，耐心等）..."
  if (Test-Path "$MindustryDir.part") { Remove-Item -Recurse -Force "$MindustryDir.part" }
  $cands = @($Mirrors | ForEach-Object { "$($_.TrimEnd('/'))/Anuken/Mindustry/archive/refs/heads/master.tar.gz" })
  $fast = Pick-Fastest $cands
  Download $fast (Join-Path $PlsDir 'mindustry.tar.gz')
  Log '  解压源码 ...'
  New-Item -ItemType Directory -Force -Path "$MindustryDir.part" | Out-Null
  tar -xf (Join-Path $PlsDir 'mindustry.tar.gz') -C "$MindustryDir.part" --strip-components=1
  if ($LASTEXITCODE -ne 0) { Die '源码解压失败' }
  Remove-Item (Join-Path $PlsDir 'mindustry.tar.gz')
  Move-Item "$MindustryDir.part" $MindustryDir
} else {
  Log "源码已存在: $MindustryDir"
}

# ---------- 6. config.conf ----------
$cfgPath = Join-Path $PlHome 'config\scripts\data\config.conf'
$content = Get-Content $cfgPath -Raw -Encoding UTF8
if ($content -match 'mindustrySourceDir = "\.\./mindustrySourceDir"') {
  Log 'config.conf 的 mindustrySourceDir 已指向源码'
} else {
  Log '修改 config.conf: mindustrySourceDir = ../mindustrySourceDir'
  $content = [regex]::Replace($content, 'mindustrySourceDir = "[^"]*"', 'mindustrySourceDir = "../mindustrySourceDir"')
  [System.IO.File]::WriteAllText($cfgPath, $content, [System.Text.UTF8Encoding]::new($false))
}

# ---------- 完成 ----------
Log '全部就绪！'
Write-Host ''
Write-Host "  你的服务器在: $PlHome"
Write-Host '  启动命令:    PL-server-plugins\run.bat（双击）'
Write-Host '  首次启动会编译全部插件，等 10 分钟以上属正常。'
Write-Host "  看到 'Server loaded' 与 'Opened a server on port 6567' 即开服成功。"
Write-Host ''