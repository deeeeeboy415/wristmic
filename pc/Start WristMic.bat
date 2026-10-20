@echo off
title WristMic receiver
cd /d "%~dp0"
set "PY=python"
where py >nul 2>nul && set "PY=py -3"
%PY% wristmic_receiver.py %*
if errorlevel 1 pause
