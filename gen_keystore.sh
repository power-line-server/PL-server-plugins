#!/usr/bin/env bash
# ============================================================
# Generate self-signed TLS keystore for WebUI (Linux/macOS)
# Usage: ./gen_keystore.sh [public_ip]
#   IP is optional: auto-detected when omitted.
#   Password is read from config.conf (sslKeystorePass).
# Re-run after server IP change, then restart server and
# re-import the new keystore.p12 in your browser.
# ============================================================
set -u
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
DATA_DIR="$SCRIPT_DIR/config/scripts/data"
KS_FILE="$DATA_DIR/webui/keystore.p12"
CONF_FILE="$DATA_DIR/config.conf"

# --- locate keytool: KEYTOOL env > PATH > JAVA_HOME > java.home ---
KEYTOOL_CMD=""
if [ -n "${KEYTOOL:-}" ] && [ -x "$KEYTOOL" ]; then
  KEYTOOL_CMD="$KEYTOOL"
elif command -v keytool >/dev/null 2>&1; then
  KEYTOOL_CMD="keytool"
elif [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/keytool" ]; then
  KEYTOOL_CMD="$JAVA_HOME/bin/keytool"
fi
if [ -z "$KEYTOOL_CMD" ]; then
  # derive real JDK home from java itself (works even when keytool is not on PATH)
  JH="$(java -XshowSettings:properties -version 2>&1 | sed -n 's/^[[:space:]]*java.home = //p' | head -1)"
  if [ -n "$JH" ] && [ -x "$JH/bin/keytool" ]; then
    KEYTOOL_CMD="$JH/bin/keytool"
  fi
fi
if [ -z "$KEYTOOL_CMD" ]; then
  echo "[ERROR] keytool not found. Set KEYTOOL env var or JAVA_HOME, or add JDK bin to PATH."
  exit 1
fi

# --- public IP: arg 1 > auto-detect (IPv4) ---
PUBLIC_IP="${1:-}"
if [ -z "$PUBLIC_IP" ]; then
  PUBLIC_IP="$(curl -4 -s --max-time 8 https://ifconfig.me 2>/dev/null)"
fi
if [ -z "$PUBLIC_IP" ]; then
  PUBLIC_IP="$(curl -4 -s --max-time 8 https://ip.sb 2>/dev/null)"
fi
if [ -z "$PUBLIC_IP" ]; then
  read -r -p "Enter public IP: " PUBLIC_IP
fi
if [ -z "$PUBLIC_IP" ]; then
  echo "[ERROR] No public IP available."
  exit 1
fi

# --- password: read sslKeystorePass from config.conf (fallback config.base.conf) ---
PASS="$(grep -o 'sslKeystorePass *= *"[^"]*"' "$CONF_FILE" 2>/dev/null | head -1 | sed 's/.*"\([^"]*\)"/\1/')"
if [ -z "$PASS" ]; then
  PASS="$(grep -o 'sslKeystorePass *= *"[^"]*"' "$DATA_DIR/config.base.conf" 2>/dev/null | head -1 | sed 's/.*"\([^"]*\)"/\1/')"
fi
if [ -z "$PASS" ]; then
  PASS="webui-ssl"
fi

# --- generate keystore (10 years, SAN: public IP + localhost) ---
mkdir -p "$DATA_DIR/webui"
rm -f "$KS_FILE"
"$KEYTOOL_CMD" -genkeypair -alias webui -keyalg RSA -keysize 2048 -storetype PKCS12 \
  -keystore "$KS_FILE" -storepass "$PASS" -validity 3650 \
  -dname "CN=Mindustry WebUI, O=PowerLine, C=CN" \
  -ext "SAN=ip:$PUBLIC_IP,ip:127.0.0.1,dns:localhost"
if [ $? -ne 0 ]; then
  echo "[ERROR] keytool failed. Check the password in config.conf (sslKeystorePass)."
  exit 1
fi

echo ""
echo "[OK] Keystore generated: $KS_FILE"
echo "[OK] SAN includes: $PUBLIC_IP, 127.0.0.1, localhost"
echo ""
echo "Next steps:"
echo "  1. Restart the server (HTTPS loads the keystore at startup)"
echo "  2. Open https://$PUBLIC_IP:8080 in your browser"
echo "  3. First visit: import $KS_FILE into \"Trusted Root Certification Authorities\""
echo "     (keystore password: $PASS - same as sslKeystorePass in config.conf)"
echo "  Note: keep keystore.p12 private, it contains the private key!"
