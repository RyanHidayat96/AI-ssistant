@echo off
rem AI-ssistant - build the APK (no Gradle needed)
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0build.ps1" %*
