@echo off
setlocal
if "%~2"=="" goto usage
if not exist "%~1\EmuHawk.exe" (echo ERROR: no existe "%~1\EmuHawk.exe"& exit /b 2)
if not exist "%~2" (echo ERROR: no existe la ROM "%~2"& exit /b 2)
set "MORTALSDK_CAPTURE_DIR=%~dp0captures"
if not exist "%MORTALSDK_CAPTURE_DIR%" mkdir "%MORTALSDK_CAPTURE_DIR%"
set "MORTALSDK_ROM_COPY=%MORTALSDK_CAPTURE_DIR%\mortalsdk-capture.gen"
copy /y "%~2" "%MORTALSDK_ROM_COPY%" >nul
if errorlevel 1 (echo ERROR: no se pudo crear la copia temporal .gen& exit /b 3)
pushd "%~1"
start "MortalSDK BizHawk capture" EmuHawk.exe --lua "%~dp0tools\bizhawk\mortalsdk_capture.lua" "%MORTALSDK_ROM_COPY%"
popd
exit /b 0
:usage
echo Uso: BizHawk-Capture.cmd RUTA_BIZHAWK ROM
exit /b 1
