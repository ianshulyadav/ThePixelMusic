@echo off
echo ========================================================
echo   Launching Android Auto Desktop Head Unit Simulator...
echo ========================================================
echo 1. Forwarding ADB port 5277 to phone...
"C:\Android\Sdk\platform-tools\adb.exe" forward tcp:5277 tcp:5277
echo 2. Launching Desktop Head Unit...
"C:\Android\Sdk\extras\google\auto\desktop-head-unit.exe"
pause

