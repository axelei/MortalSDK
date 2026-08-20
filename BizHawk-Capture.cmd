@echo off
setlocal
if "%~2"=="" goto usage
if not exist "%~1\EmuHawk.exe" (echo ERROR: no existe "%~1\EmuHawk.exe"& exit /b 2)
if not exist "%~2" (echo ERROR: no existe la ROM "%~2"& exit /b 2)
set "MORTALSDK_CAPTURE_DIR=%~dp0captures"
pushd "%~1"
start "MortalSDK BizHawk capture" EmuHawk.exe --lua "%~dp0tools\bizhawk\mortalsdk_capture.lua" "%~2"
popd
exit /b 0
:usage
echo Uso: BizHawk-Capture.cmd RUTA_BIZHAWK ROM
exit /b 1
