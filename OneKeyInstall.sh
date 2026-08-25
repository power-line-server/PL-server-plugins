#!/usr/bin/env bash
# ============================================================
# OneKeyInstall.sh — 一键安装 PL-server-plugins（Linux / Termux）
# 会做的事：
#   1. 安装 git（系统包管理器，apt/dnf/yum/pacman/apk/zypper/pkg）
#   2. 下载便携 JDK26 到 ~/.pls/jdk（免 root，Adoptium 发行）
#   3. 克隆本仓库到 ~/PL-server-plugins
#   4. 从 MindustyX 最新发行版下载 server 文件，改名 server.jar
#   5. 下载 Mindust 源码到 ~/mindustrySourceDir
#   6. 把 config.conf 的 mindustrySourceDir 指向它
# 之后运行 ~/PL-server-plugins/run.sh 即可开服。
#
# 特性：幂等。中途 Ctrl-C、断网、再次运行都不会搞坏，
# 已完成的步骤自动跳过，下载失败会重试。
# 用法: bash OneKeyInstall.sh [--force]   （--force 重装 JDK/源码等）
# ============================================================
set -u

REPO_URL="https://github.com/power-line-server/PL-server-plugins.git"
MINDUSX_REPO="TinyLake/MindustryX"
PLHOME="${PLSERVER_HOME:-$HOME/PL-server-plugins}"
MINDUS_DIR="${PLSERVER_SOURCE:-$HOME/mindustrySourceDir}"
PLSDIR="$HOME/.pls"
JDK_DIR="$PLSDIR/jdk"
FORCE=0
[ "${1:-}" = "--force" ] && FORCE=1

# GitHub 加速镜像（按需增删，测速取最快；第一个总是原站）
MIRRORS=(
  "https://github.com"
  "https://ghfast.top/https://github.com"
  "https://gh-proxy.com/https://github.com"
  "https://ghproxy.net/https://github.com"
  "https://mirror.ghproxy.com/https://github.com"
  "https://ghproxy.cc/https://github.com"
)
# Oracle JDK26 官方直链（latest 目录固定文件名，按架构拼）
ORACLE_JDK="https://download.oracle.com/java/26/latest"

log()  { printf '\033[1;36m[PLS]\033[0m %s\n' "$*"; }
die()  { printf '\033[1;31m[PLS]\033[0m %s\n' "$*" >&2; exit 1; }

# 测速：给一串 URL，返回下载速度最快的那个（只测前 1MB）
pick_fastest() {
  local best="" bestsp=0 u sp
  for u in "$@"; do
    sp=$(curl -sL --connect-timeout 5 -m 10 -o /dev/null -r 0-1048575 -w '%{speed_download}' "$u" 2>/dev/null || echo 0)
    sp=${sp%.*}
    log "  测速 ${sp:-0}B/s  $u"
    if [ "${sp:-0}" -gt "$bestsp" ] 2>/dev/null; then bestsp=$sp; best=$u; fi
  done
  [ -n "$best" ] && echo "$best" || echo "${1:-}"
}

# 带重试的下载
dl() {  # dl <url> <输出文件> [重试次数]
  local url="$1" out="$2" tries="${3:-3}" i
  for i in $(seq 1 "$tries"); do
    log "  下载中($i/$tries): $url"
    if curl -fL --connect-timeout 10 -m 900 -o "$out.part" "$url"; then
      mv "$out.part" "$out"; return 0
    fi
    rm -f "$out.part"; sleep 3
  done
  return 1
}

has_java26() {
  local j="$JDK_DIR/bin/java"
  [ -x "$j" ] && "$j" -version 2>&1 | grep -q '"26\.'
}
has_git() { command -v git >/dev/null 2>&1; }

mkdir -p "$PLSDIR"

# ---------- 1. git ----------
if has_git; then
  log "git 已安装: $(git --version)"
else
  log "安装 git ..."
  if command -v pkg >/dev/null 2>&1; then
    pkg update -y >/dev/null 2>&1; pkg install -y git || die "git 安装失败，请手动执行: pkg install -y git"
  elif command -v apt-get >/dev/null 2>&1; then
    sudo apt-get update -y >/dev/null 2>&1; sudo apt-get install -y git || die "git 安装失败，请手动执行: sudo apt-get install -y git"
  elif command -v dnf >/dev/null 2>&1; then
    sudo dnf install -y git || die "git 安装失败，请手动执行: sudo dnf install -y git"
  elif command -v pacman >/dev/null 2>&1; then
    sudo pacman -Sy --noconfirm git || die "git 安装失败，请手动执行: sudo pacman -S git"
  elif command -v apk >/dev/null 2>&1; then
    sudo apk add git || die "git 安装失败，请手动执行: sudo apk add git"
  elif command -v yum >/dev/null 2>&1; then
    sudo yum install -y git || die "git 安装失败，请手动执行: sudo yum install -y git"
  elif command -v zypper >/dev/null 2>&1; then
    sudo zypper --non-interactive install git || die "git 安装失败，请手动执行: sudo zypper install git"
  else
    die "没认出你的发行版，请手动安装 git 后再运行"
  fi
fi

# ---------- 2. JDK26（便携，免 root） ----------
if ! has_java26 || [ "$FORCE" = 1 ]; then
  log "下载便携 JDK26 到 $JDK_DIR ..."
  rm -rf "$JDK_DIR" "${JDK_DIR}.part"
  arch=$(uname -m)
  case "$arch" in
    x86_64)  jarch="x64" ;;
    aarch64|arm64) jarch="aarch64" ;;
    *) die "不支持的架构: $arch" ;;
  esac
  jurls=("$ORACLE_JDK/jdk-26_linux-${jarch}_bin.tar.gz")
  jfast=$(pick_fastest "${jurls[@]}")
  dl "$jfast" "$PLSDIR/jdk.tar.gz" || die "JDK 下载失败。可手动把 JDK26 解压到 ~/.pls/jdk 后重跑"
  mkdir -p "$JDK_DIR.part"
  tar -xzf "$PLSDIR/jdk.tar.gz" -C "$JDK_DIR.part" --strip-components=1 || die "JDK 解压失败"
  rm -f "$PLSDIR/jdk.tar.gz"
  mv "$JDK_DIR.part" "$JDK_DIR"
else
  log "JDK26 已就绪: $JDK_DIR"
fi
JAVA="$JDK_DIR/bin/java"
"$JAVA" -version >/dev/null 2>&1 || die "JDK 不可用: $JAVA"

# ---------- 3. 仓库 ----------
if [ ! -f "$PLHOME/config/scripts/data/config.conf" ]; then
  log "克隆仓库到 $PLHOME ..."
  cloned=0
  for m in "${MIRRORS[@]}"; do
    u="${m%/}/power-line-server/PL-server-plugins.git"
    log "  尝试: $u"
    if git clone --depth 1 "$u" "$PLHOME" 2>/dev/null; then cloned=1; break; fi
    rm -rf "$PLHOME"
  done
  if [ "$cloned" = 0 ]; then
    log "  镜像都失败，再试 git clone 原站 ..."
    git clone --depth 1 "$REPO_URL" "$PLHOME" || { rm -rf "$PLHOME"; die "仓库克隆失败，请检查网络后重跑"; }
  fi
  [ -f "$PLHOME/config/scripts/data/config.conf" ] || die "仓库克隆不完整，请重跑"
else
  log "仓库已存在: $PLHOME"
fi
cd "$PLHOME" || die "进不了目录 $PLHOME"
chmod +x run.sh watchdog.sh OneKeyInstall.sh 2>/dev/null

# ---------- 4. server.jar（MindustryX 最新发行版） ----------
if [ ! -f "$PLHOME/server.jar" ] || [ "$FORCE" = 1 ]; then
  log "获取 MindustyX 最新发行版信息 ..."
  jsondata=""
  # API 走 api.github.com 原站，失败再试镜像的 api 路径
  for api in "https://api.github.com" "${MIRRORS[@]/github.com/api.github.com}"; do
    jsondata=$(curl -sL --connect-timeout 8 -m 30 "$api/repos/$MINDUSX_REPO/releases/latest" 2>/dev/null || true)
    echo "$jsondata" | grep -q '"browser_download_url"' && break
    jsondata=""
  done
  [ -n "$jsondata" ] || die "拿不到 MindustyX 版本信息，请检查网络后重跑"
  asset=$(echo "$jsondata" | grep -oE '"name": *"[^"]*server[^"]*"' | head -1 | sed -E 's/.*"[^"]*": *"([^"]*)"/\1/')
  [ -z "$asset" ] && asset=$(echo "$jsondata" | grep -oE '"name": *"[^"]*\.jar"' | head -1 | sed -E 's/.*"[^"]*": *"([^"]*)"/\1/')
  [ -z "$asset" ] && die "发行版里没找到 server 文件"
  log "找到资产: $asset"
  # 提取该资产对应的下载 URL（按文件名精确匹配）
  raw_url=$(echo "$jsondata" | grep -oE '"browser_download_url": *"[^"]*"' | grep -F "$asset" | head -1)
  dlurl=$(echo "$raw_url" | sed -E 's/.*"https/https/; s/"$//')
  [ -n "$dlurl" ] || die "拿不到下载地址"
  # 原站地址则生成镜像候选并测速
  case "$dlurl" in
    https://github.com/*)
      dlurls=()
      for m in "${MIRRORS[@]}"; do
        dlurls+=("${m%/}/${dlurl#https://github.com/}")
      done
      fast=$(pick_fastest "${dlurls[@]}")
      ;;
    *) fast="$dlurl" ;;
  esac
  dl "$fast" "$PLHOME/server.jar" || die "server.jar 下载失败，请重跑"
else
  log "server.jar 已存在"
fi

# ---------- 5. 游戏源码 ----------
if [ ! -d "$MINDUS_DIR/core/assets" ] || [ "$FORCE" = 1 ]; then
  log "下载 Mindust 源码到 $MINDUS_DIR（首次约 100MB+，耐心等）..."
  cd "$HOME" || true
  rm -rf "$MINDUS_DIR.part"
  urls=()
  for m in "${MIRRORS[@]}"; do
    urls+=("${m%/}/Anuken/Mindustry/archive/refs/heads/master.tar.gz")
  done
  fast=$(pick_fastest "${urls[@]}")
  dl "$fast" "$PLSDIR/mindustry.tar.gz" || die "源码下载失败，请重跑"
  mkdir -p "$MINDUS_DIR.part"
  tar -xzf "$PLSDIR/mindustry.tar.gz" -C "$MINDUS_DIR.part" --strip-components=1 || die "源码解压失败"
  rm -f "$PLSDIR/mindustry.tar.gz"
  mv "$MINDUS_DIR.part" "$MINDUS_DIR"
else
  log "源码已存在: $MINDUS_DIR"
fi

# ---------- 6. config.conf 指向源码 ----------
CFG="$PLHOME/config/scripts/data/config.conf"
if grep -q 'mindustrySourceDir = "'"../mindustrySourceDir"'"' "$CFG"; then
  log "config.conf 的 mindustrySourceDir 已指向源码"
else
  log "修改 config.conf: mindustrySourceDir = ../mindustrySourceDir"
  sed -i 's|mindustrySourceDir = "[^"]*"|mindustrySourceDir = "../mindustrySourceDir"|' "$CFG"
fi

# ---------- 完成 ----------
log "全部就绪！"
echo ""
echo "  你的服务器在: $PLHOME"
echo "  启动命令:    bash $PLHOME/run.sh"
echo "  首次启动会编译全部插件，等 10 分钟以上属正常。"
echo "  看到 'Server loaded' 与 'Opened a server on port 6567' 即开服成功。"
echo ""