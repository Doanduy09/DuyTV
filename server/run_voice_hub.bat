@echo off
chcp 65001 > nul
title DuyTV Voice Hub - Cầu Nối Giọng Nói Cho ESP32 & Tivi
echo =====================================================================
echo  DUYTV VOICE HUB SERVER - 100%% ĐIỀU KHIỂN GIỌNG NÓI TIẾNG VIỆT
echo  Nhận âm thanh từ ESP32-C3 HW-953AB (UDP 8995)
echo  Điều khiển Tivi Sharp (192.168.1.172) & Shield TV (192.168.1.218)
echo =====================================================================
echo.

where uv >nul 2>nul
if %ERRORLEVEL% EQU 0 (
    echo [Khoi Dong] Chay bang trinh quan ly uv python...
    uv run python duytv_voice_hub.py
) else (
    echo [Khoi Dong] Chay bang trinh python mac dinh...
    python duytv_voice_hub.py
)

pause
