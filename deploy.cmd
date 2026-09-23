@echo off
rem ============================================================
rem  AI-ssistant - build + deploy otomatis ke HP
rem  Klik dua kali file ini (atau jalankan: deploy.cmd)
rem  Ubah SERIAL kalau HP-nya beda.
rem ============================================================
setlocal
cd /d "%~dp0"
set "SDK=C:\Users\exery\Documents\tools\Causentry\tools\sdk"
set "SERIAL=6a0706f0"
set "PKG=com.aissistant.app"

echo === 1) cek HP terhubung ===
adb -s %SERIAL% get-state >nul 2>&1
if errorlevel 1 (
  echo HP %SERIAL% TIDAK terdeteksi. Colok kabel USB / nyalakan USB debugging, lalu ulangi.
  pause & exit /b 1
)

echo === 2) build + install ===
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0build.ps1" -Sdk "%SDK%" -Deploy -Serial %SERIAL%
if errorlevel 1 (
  echo.
  echo GAGAL: build atau install error. Baca pesan di atas ^(biasanya "error:" di file .java^).
  pause & exit /b 1
)

echo === 3) izin + buka app ===
adb -s %SERIAL% shell appops set %PKG% SYSTEM_ALERT_WINDOW allow >nul 2>&1
adb -s %SERIAL% shell pm grant %PKG% android.permission.POST_NOTIFICATIONS >nul 2>&1
adb -s %SERIAL% shell am force-stop %PKG%
adb -s %SERIAL% shell am start -n %PKG%/.MainActivity >nul 2>&1

echo === 4) versi terpasang ===
adb -s %SERIAL% shell dumpsys package %PKG% | findstr versionName

echo.
echo SELESAI. App sudah dibuka di HP.
pause
