@echo off
rem ============================================================
rem Generate self-signed TLS keystore for WebUI (Windows)
rem Usage: gen_keystore.bat [public_ip]
rem   IP is optional: auto-detected when omitted.
rem   Password is read from config.conf (sslKeystorePass).
rem Re-run after server IP change, then restart server and
rem re-import the new keystore.p12 in your browser.
rem ============================================================
setlocal
set "SERVER_DIR=%~dp0"
set "DATA_DIR=%SERVER_DIR%config\scripts\data"
set "KS_FILE=%DATA_DIR%\webui\keystore.p12"
set "CONF_FILE=%DATA_DIR%\config.conf"

rem --- locate keytool: KEYTOOL env > PATH > JAVA_HOME > java.home ---
set "KEYTOOL_CMD="
if defined KEYTOOL if exist "%KEYTOOL%" set "KEYTOOL_CMD=%KEYTOOL%"
if not defined KEYTOOL_CMD (
  where keytool >nul 2>nul && set "KEYTOOL_CMD=keytool"
)
if not defined KEYTOOL_CMD if defined JAVA_HOME if exist "%JAVA_HOME%\bin\keytool.exe" set "KEYTOOL_CMD=%JAVA_HOME%\bin\keytool.exe"
if not defined KEYTOOL_CMD (
  rem derive real JDK home from java itself (works even when keytool is not on PATH)
  for /f "tokens=2 delims==" %%h in ('java -XshowSettings:properties -version 2^>^&1 ^| findstr "java.home"') do (
    for /f "tokens=*" %%a in ("%%h") do (
      if exist "%%a\bin\keytool.exe" set "KEYTOOL_CMD=%%a\bin\keytool.exe"
    )
  )
)
if not defined KEYTOOL_CMD (
  echo [ERROR] keytool not found. Set KEYTOOL env var or JAVA_HOME, or add JDK bin to PATH.
  exit /b 1
)

rem --- public IP: arg 1 > auto-detect (IPv4) ---
set "PUBLIC_IP=%~1"
if not defined PUBLIC_IP (
  for /f "delims=" %%i in ('curl -4 -s --max-time 8 https://ifconfig.me 2^>nul') do set "PUBLIC_IP=%%i"
)
if not defined PUBLIC_IP (
  for /f "delims=" %%i in ('curl -4 -s --max-time 8 https://ip.sb 2^>nul') do set "PUBLIC_IP=%%i"
)
if not defined PUBLIC_IP (
  set /p "PUBLIC_IP=Enter public IP: "
)
if not defined PUBLIC_IP (
  echo [ERROR] No public IP available.
  exit /b 1
)

rem --- password: read sslKeystorePass from config.conf (fallback config.base.conf) ---
set "PASS="
for /f "tokens=2 delims==" %%p in ('findstr "sslKeystorePass" "%CONF_FILE%" 2^>nul') do set "PASS=%%p"
if not defined PASS (
  for /f "tokens=2 delims==" %%p in ('findstr "sslKeystorePass" "%DATA_DIR%\config.base.conf" 2^>nul') do set "PASS=%%p"
)
if defined PASS set "PASS=%PASS: =%"
if defined PASS set "PASS=%PASS:"=%"
if not defined PASS set "PASS=webui-ssl"

rem --- generate keystore (10 years, SAN: public IP + localhost) ---
if not exist "%DATA_DIR%\webui" mkdir "%DATA_DIR%\webui"
if exist "%KS_FILE%" del /q "%KS_FILE%"
"%KEYTOOL_CMD%" -genkeypair -alias webui -keyalg RSA -keysize 2048 -storetype PKCS12 -keystore "%KS_FILE%" -storepass "%PASS%" -validity 3650 -dname "CN=Mindustry WebUI, O=PowerLine, C=CN" -ext "SAN=ip:%PUBLIC_IP%,ip:127.0.0.1,dns:localhost"
if errorlevel 1 (
  echo [ERROR] keytool failed. Check the password in config.conf ^(sslKeystorePass^).
  exit /b 1
)

echo.
echo [OK] Keystore generated: %KS_FILE%
echo [OK] SAN includes: %PUBLIC_IP%, 127.0.0.1, localhost
echo.
echo Next steps:
echo   1. Restart the server (HTTPS loads the keystore at startup)
echo   2. Open https://%PUBLIC_IP%:8080 in your browser
echo   3. First visit: import %KS_FILE% into "Trusted Root Certification Authorities"
echo      (keystore password: %PASS% - same as sslKeystorePass in config.conf)
echo   Note: keep keystore.p12 private, it contains the private key!
endlocal
