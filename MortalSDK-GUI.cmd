@echo off
setlocal
set "SDK_DIR=%~dp0"
set "ROM_PATH=%~1"
set "CONFIG_PATH=%~2"
if not defined ROM_PATH set /p "ROM_PATH=Ruta de la ROM: "
if not defined CONFIG_PATH set "CONFIG_PATH=%SDK_DIR%dist\configs\Mortal Kombat Arcade Edition v2-0.properties"
java -jar "%SDK_DIR%dist\MortalSDK.jar" gui "%ROM_PATH%" "%CONFIG_PATH%"
if errorlevel 1 pause
