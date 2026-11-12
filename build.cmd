@echo off
setlocal
cd /d "%~dp0"
call gradlew.bat clean test plugin
exit /b %errorlevel%
