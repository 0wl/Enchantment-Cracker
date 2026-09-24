@echo off
REM Builds the Enchantment Cracker mod jar into ..\output\
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0build.ps1" %*
