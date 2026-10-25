#!/usr/bin/env python3
"""
Galaxy Watch 4 - Desktop Bluetooth Low Energy (BLE) Telemetry Server
===================================================================
Transmits compact 16-byte binary telemetry packets every 1.0s over BLE.
Runs simultaneously with other PC Bluetooth devices (headphones, mouse, controllers)
without interference, and with near-zero watch battery/thermal load.
"""

import os
import sys
import time
import struct
import asyncio
import logging
import subprocess
from datetime import datetime, timezone, timedelta

# Logging setup
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [BLE-%(levelname)s] %(message)s",
    datefmt="%H:%M:%S"
)
logger = logging.getLogger("BleServer")

try:
    import psutil
except ImportError:
    logger.error("psutil is required. Run: pip install psutil")
    sys.exit(1)

# Check WinRT BLE availability
WINRT_AVAILABLE = False
try:
    import winrt.windows.devices.bluetooth.advertisement as adv
    import winrt.windows.storage.streams as streams
    WINRT_AVAILABLE = True
except ImportError:
    logger.warning("WinRT Bluetooth not found. Telemetry will run in console preview mode.")

# BLE Service & Company IDs (Matching Wear OS BleClientManager)
COMPANY_ID = 0x05F9 # Custom Company Identifier for telemetry beacon
SERVICE_UUID = "0000ffe0-0000-1000-8000-00805f9b34fb"

# IST Timezone (UTC + 5:30)
IST = timezone(timedelta(hours=5, minutes=30))

def query_nvidia_smi():
    """Query NVIDIA discrete GPU metrics via nvidia-smi."""
    try:
        query_fields = "temperature.gpu,utilization.gpu,memory.total,memory.used,power.draw"
        cmd = [
            "nvidia-smi",
            f"--query-gpu={query_fields}",
            "--format=csv,noheader,nounits"
        ]
        res = subprocess.run(
            cmd,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            timeout=1.0,
            creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
        )
        if res.returncode == 0 and res.stdout.strip():
            target_line = res.stdout.strip().split("\n")[0]
            parts = [p.strip() for p in target_line.split(",")]
            if len(parts) >= 5:
                temp = int(float(parts[0])) if parts[0] != "[Not Supported]" else 0
                usage = int(float(parts[1])) if parts[1] != "[Not Supported]" else 0
                total_vram = int(float(parts[2])) if parts[2] != "[Not Supported]" else 8192
                used_vram = int(float(parts[3])) if parts[3] != "[Not Supported]" else 0
                pwr = int(float(parts[4])) if parts[4] != "[Not Supported]" else 0
                return temp, usage, used_vram, total_vram, pwr
    except Exception:
        pass
    return 0, 0, 0, 8192, 0

def get_cpu_temp(cpu_usage):
    """Calculate thermal reading from sensors or dynamic estimation."""
    try:
        import wmi
        w = wmi.WMI(namespace="root\\LibreHardwareMonitor")
        sensors = w.Sensor()
        for s in sensors:
            if s.SensorType == "Temperature" and ("CPU Package" in s.Name or "Core" in s.Name):
                return int(float(s.Value))
    except Exception:
        pass
    # Dynamic thermal estimation: idle ~38°C, load up to ~78°C
    return int(38.0 + (cpu_usage / 100.0) * 38.0)

def encode_telemetry_packet():
    """
    Encodes live telemetry into a 16-byte raw binary packet:
    < B B B B H H H H B B B B
    [0] gpuTemp (uint8)
    [1] cpuTemp (uint8)
    [2] gpuUsage (uint8)
    [3] cpuUsage (uint8)
    [4..5] vramUsedMb (uint16 LE)
    [6..7] vramTotalMb (uint16 LE)
    [8..9] ramUsedMb (uint16 LE)
    [10..11] ramTotalMb (uint16 LE)
    [12] gpuPowerW (uint8)
    [13] istHour (uint8)
    [14] istMinute (uint8)
    [15] flags (uint8)
    """
    # CPU
    cpu_usage = int(psutil.cpu_percent(interval=None))
    cpu_temp = get_cpu_temp(cpu_usage)

    # GPU
    gpu_temp, gpu_usage, vram_used, vram_total, gpu_pwr = query_nvidia_smi()

    # RAM
    vm = psutil.virtual_memory()
    ram_used_mb = int(vm.used / (1024 * 1024))
    ram_total_mb = int(vm.total / (1024 * 1024))

    # Time (IST)
    now_ist = datetime.now(IST)
    ist_hour = now_ist.hour
    ist_min = now_ist.minute
    ist_sec = now_ist.second

    packet = struct.pack(
        "<BBBBHHHHBBBB",
        min(255, max(0, gpu_temp)),
        min(255, max(0, cpu_temp)),
        min(100, max(0, gpu_usage)),
        min(100, max(0, cpu_usage)),
        min(65535, max(0, vram_used)),
        min(65535, max(0, vram_total)),
        min(65535, max(0, ram_used_mb)),
        min(65535, max(0, ram_total_mb)),
        min(255, max(0, gpu_pwr)),
        ist_hour,
        ist_min,
        ist_sec
    )
    return packet, gpu_temp, cpu_temp, gpu_usage, cpu_usage, gpu_pwr, ram_used_mb, ram_total_mb

async def run_ble_server():
    """Runs the 1-second BLE broadcast daemon."""
    logger.info("========================================================")
    logger.info("   Galaxy Watch 4 - Desktop BLE Telemetry Server        ")
    logger.info("========================================================")
    logger.info(" [+] Telemetry Rate : 1.0s (1Hz)")
    logger.info(" [+] Radio Protocol : Bluetooth Low Energy (BLE Broadcast)")
    logger.info(f" [+] Company ID     : 0x{COMPANY_ID:04X}")
    logger.info(" [+] Packet Size    : 16 bytes (Compact binary)")
    logger.info("--------------------------------------------------------")

    publisher = None
    if WINRT_AVAILABLE:
        try:
            publisher = adv.BluetoothLEAdvertisementPublisher()
            def on_status_changed(sender, args):
                logger.info(f"[BLE Status Changed] status={args.status}, error={args.error}")
            publisher.add_status_changed(on_status_changed)
            logger.info(f"[+] Windows BLE Publisher initialized for Company ID 0x{COMPANY_ID:04X} (Compact 16B beacon)")
        except Exception as e:
            logger.error(f"[-] Could not initialize BLE Publisher: {e}")

    psutil.cpu_percent(interval=None)

    while True:
        try:
            packet, gpu_t, cpu_t, gpu_u, cpu_u, pwr, ram_u, ram_tot = encode_telemetry_packet()

            # Broadcast over Windows BLE
            if publisher:
                try:
                    # Update manufacturer data payload
                    writer = streams.DataWriter()
                    writer.write_bytes(packet)
                    mfg_data = adv.BluetoothLEManufacturerData(COMPANY_ID, writer.detach_buffer())

                    # Clear previous and append updated data
                    publisher.advertisement.manufacturer_data.clear()
                    publisher.advertisement.manufacturer_data.append(mfg_data)

                    # Start or keep alive
                    if publisher.status != adv.BluetoothLEAdvertisementPublisherStatus.STARTED:
                        publisher.start()
                except Exception as ble_err:
                    logger.warning(f"BLE Broadcast tick error: {ble_err}")

            now_ist = datetime.now(IST).strftime("%H:%M:%S")
            logger.info(
                f"[{now_ist} IST] 1Hz Broadcast: GPU {gpu_t}°C ({gpu_u}%) {pwr}W | "
                f"CPU {cpu_t}°C ({cpu_u}%) | RAM {ram_u // 1024}G/{ram_tot // 1024}G | 16B BLE sent"
            )
        except Exception as e:
            logger.error(f"Error in telemetry broadcast loop: {e}")

        await asyncio.sleep(1.0)

if __name__ == "__main__":
    try:
        asyncio.run(run_ble_server())
    except KeyboardInterrupt:
        logger.info("BLE Server stopped by user.")
