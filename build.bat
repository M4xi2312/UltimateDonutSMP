@echo off
call gradlew.bat clean build
exit /b %ERRORLEVEL%
