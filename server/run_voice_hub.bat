@echo off
chcp 65001 > nul
title DuyTV Voice Hub - Trợ Lý Lâm (Auto-Discovery & Full Remote Control)
echo =====================================================================
echo  DUYTV VOICE HUB SERVER - TRỢ LÝ GIỌNG NÓI "LÂM" ĐIỀU KHIỂN TIVI
echo  Tự động nhận diện Tivi Sharp, Shield TV, Box trên Router LAN
echo  Nhận âm thanh từ ESP32-C3 HW-953AB (UDP 8995) ^& Samsung Remote (HTTP 8996)
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
