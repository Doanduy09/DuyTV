@echo off
chcp 65001 > nul
title Nạp Firmware ESP32-C3 HW-953AB (DuyTV Voice Puck)
echo =====================================================================
echo  TỰ ĐỘNG NẠP FIRMWARE ESP32-C3 (HW-953AB) QUA CỔNG COM3
echo  Firmware: Voice Puck + Micro I2S INMP441
echo  Wi-Fi: TPT Lam / TPT Lam 5G ^| Server: 192.168.1.189:8995
echo =====================================================================
echo.

set PORT=COM3
if not "%1"=="" set PORT=%1

echo [*] Dang nap firmware vao ESP32-C3 tren cong %PORT%...
uv run --with esptool python -m esptool --chip esp32c3 --port %PORT% --baud 460800 --before default_reset --after hard_reset write_flash -z --flash_mode dio --flash_freq 80m --flash_size 4MB 0x0 bin\bootloader.bin 0x8000 bin\partitions.bin 0x10000 bin\firmware.bin

if %ERRORLEVEL% EQU 0 (
    echo.
    echo =====================================================================
    echo  [THANH CONG] Da nap xong 100%% firmware vao ESP32-C3 HW-953AB!
    echo  Neu bo mach can khoi dong lai: Hay bam nut RST hoac rut ra cam lai.
    echo =====================================================================
) else (
    echo.
    echo [LOI] Khong the nap. Hay giu nut BOOT ^(IO9^) roi cam lai cap Type-C!
)

pause
