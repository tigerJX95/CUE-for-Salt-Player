@echo off
setlocal
cd /d "%~dp0"
if "%~1"=="" (
  echo Usage: release.cmd "C:\path\to\SaltPlayer-ffmpeg-x64.dll"
  exit /b 1
)
call gradlew.bat clean releasePlugin "-PspwHostArchive=%~1"
exit /b %errorlevel%
