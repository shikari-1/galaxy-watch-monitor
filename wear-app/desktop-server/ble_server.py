#!/usr/bin/env python3
"""
Galaxy Watch 4 - Desktop Bluetooth Low Energy (BLE) Telemetry GATT Server
========================================================================
Hosts a standard BLE GATT Server on Windows broadcasting live PC hardware telemetry.
Pushes compact 16-byte binary telemetry packets every 1.0s via GATT Notifications.
Zero scan throttling, deterministic 1Hz cadence, and low watch power/thermal overhead.
"""

import os
import sys
import time
import struct
import asyncio
import logging
import subprocess
import uuid
import ctypes
from datetime import datetime, timezone, timedelta

# Logging setup
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [BLE-%(levelname)s] %(message)s",
    datefmt="%H:%M:%S"
)
logger = logging.getLogger("BleGattServer")

def check_and_request_admin():
    """If not running as Administrator, prompt Windows UAC and re-launch elevated."""
    if os.name != 'nt':
        return True
    try:
        is_admin = ctypes.windll.shell32.IsUserAnAdmin()
    except Exception:
        is_admin = False

    if not is_admin:
        if "--no-elevation" in sys.argv:
            return False
        logger.info("[*] Administrator privileges required for AMD hardware sensors.")
        logger.info("[*] Prompting Windows UAC for elevation...")
        params = " ".join([f'"{arg}"' for arg in sys.argv])
        # ShellExecuteW with verb 'runas' prompts Windows UAC dialog
        ret = ctypes.windll.shell32.ShellExecuteW(
            None,
            "runas",
            sys.executable,
            params,
            None,
            1  # SW_SHOWNORMAL
        )
        if ret > 32:
            # Elevated process successfully started; exit this un-elevated launcher
            sys.exit(0)
        else:
            logger.warning("[!] UAC elevation was declined by user. Running with limited permissions.")
            return False
    return True

try:
    import psutil
except ImportError:
    logger.error("psutil is required. Run: pip install psutil")
    sys.exit(1)

# Check WinRT BLE availability
WINRT_AVAILABLE = False
try:
    import winrt.windows.devices.bluetooth.genericattributeprofile as gap
    import winrt.windows.storage.streams as streams
    WINRT_AVAILABLE = True
except ImportError:
    logger.warning("WinRT Bluetooth GATT APIs not found. Please ensure Windows 10/11 with winrt package.")

# Standard 128-bit UUIDs for PC Hardware Monitor Telemetry Service
SERVICE_UUID_STR = "0000ffe0-0000-1000-8000-00805f9b34fb"
CHARACTERISTIC_UUID_STR = "0000ffe1-0000-1000-8000-00805f9b34fb"

SERVICE_UUID = uuid.UUID(SERVICE_UUID_STR)
CHARACTERISTIC_UUID = uuid.UUID(CHARACTERISTIC_UUID_STR)

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

_lhm_wmi = None

def ensure_hardware_monitor():
    """Ensure LibreHardwareMonitor is running in the background without needing a scheduled task."""
    try:
        for p in psutil.process_iter(['name']):
            try:
                pname = p.info['name']
                if pname and 'LibreHardwareMonitor' in pname:
                    return
            except Exception:
                pass

        project_root = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
        lhm_exe = os.path.join(project_root, "tools", "librehardwaremonitor", "LibreHardwareMonitor.exe")
        if os.path.exists(lhm_exe):
            logger.info(f"[+] Launching LibreHardwareMonitor from {lhm_exe}...")
            subprocess.Popen(
                [lhm_exe],
                cwd=os.path.dirname(lhm_exe),
                creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
            )
    except Exception as e:
        logger.debug(f"Could not auto-start LibreHardwareMonitor: {e}")

def get_cpu_temp(cpu_usage):
    """Retrieve genuine CPU silicon temperature via LibreHardwareMonitor WMI, or fallback."""
    global _lhm_wmi
    try:
        if _lhm_wmi is None:
            import win32com.client
            locator = win32com.client.Dispatch("WbemScripting.SWbemLocator")
            for ns in ["root\\LibreHardwareMonitor", "root\\OpenHardwareMonitor"]:
                try:
                    _lhm_wmi = locator.ConnectServer(".", ns)
                    break
                except Exception:
                    pass

        if _lhm_wmi is not None:
            sensors = _lhm_wmi.ExecQuery("SELECT Name, Value FROM Sensor WHERE SensorType = 'Temperature'")
            # 1. Prioritize true AMD Ryzen Silicon Junction / Package temperature
            for s in sensors:
                name = str(s.Name)
                if "Core (Tctl/Tdie)" in name or "CPU Package" in name:
                    val = float(s.Value)
                    if val > 0:
                        return int(round(val))
            # 2. Secondary check for individual core readings
            for s in sensors:
                name = str(s.Name)
                if "Core #" in name or "Core" in name:
                    val = float(s.Value)
                    if val > 0:
                        return int(round(val))
    except Exception:
        _lhm_wmi = None

    # Dynamic thermal estimation fallback if hardware monitor is starting up
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
    [15] istSecond (uint8)
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

async def run_gatt_server():
    """Hosts the Windows BLE GATT Server and transmits 1.0s telemetry notifications."""
    logger.info("========================================================")
    logger.info("   Galaxy Watch 4 - Desktop BLE GATT Telemetry Server   ")
    logger.info("========================================================")
    logger.info(" [+] Telemetry Rate  : 1.0s (1Hz)")
    logger.info(" [+] Protocol        : Bluetooth Low Energy (Connected GATT)")
    logger.info(f" [+] Service UUID    : {SERVICE_UUID_STR}")
    logger.info(f" [+] Char UUID       : {CHARACTERISTIC_UUID_STR}")
    logger.info(" [+] Packet Size     : 16 bytes (Compact binary)")
    logger.info("--------------------------------------------------------")

    if not WINRT_AVAILABLE:
        logger.error("WinRT Bluetooth GATT library is required to run the server.")
        return

    # 1. Create GATT Service Provider
    provider_res = await gap.GattServiceProvider.create_async(SERVICE_UUID)
    if provider_res.error != 0:
        logger.error(f"[-] Could not create GattServiceProvider (error code {provider_res.error})")
        return
    provider = provider_res.service_provider

    # 2. Configure Local Characteristic with NOTIFY & READ properties
    char_params = gap.GattLocalCharacteristicParameters()
    char_params.characteristic_properties = (
        gap.GattCharacteristicProperties.NOTIFY |
        gap.GattCharacteristicProperties.READ
    )
    char_params.read_protection_level = gap.GattProtectionLevel.PLAIN

    char_res = await provider.service.create_characteristic_async(CHARACTERISTIC_UUID, char_params)
    if char_res.error != 0:
        logger.error(f"[-] Could not create GATT Characteristic (error code {char_res.error})")
        return
    characteristic = char_res.characteristic

    # 3. Add listener for client subscription changes
    def on_subscribers_changed(sender, args):
        clients = sender.subscribed_clients
        logger.info(f"[+] Client subscription state changed: {len(clients)} client(s) currently subscribed.")
    characteristic.add_subscribed_clients_changed(on_subscribers_changed)

    # 4. Handle Read Requests (Fallback if client reads directly)
    def on_read_requested(sender, args):
        deferral = args.get_deferral()
        try:
            req_op = args.get_request_async()
            # Respond with immediate telemetry
            packet, *_ = encode_telemetry_packet()
            writer = streams.DataWriter()
            writer.write_bytes(packet)
            req = req_op.get()
            req.respond_with_value(writer.detach_buffer())
        except Exception as err:
            logger.warning(f"Error handling read request: {err}")
        finally:
            deferral.complete()
    characteristic.add_read_requested(on_read_requested)

    # 5. Start Advertising Service
    adv_params = gap.GattServiceProviderAdvertisingParameters()
    adv_params.is_connectable = True
    adv_params.is_discoverable = True
    provider.start_advertising(adv_params)
    logger.info(f"[+] PC GATT Server is advertising. Waiting for Galaxy Watch 4 to connect...")

    # Ensure hardware monitor is running
    ensure_hardware_monitor()

    # Prime psutil CPU percent calculation
    psutil.cpu_percent(interval=None)

    try:
        while True:
            # Hibernation / Sleep Recovery: Check if Windows cycled Bluetooth adapter
            try:
                if provider.advertisement_status != gap.GattServiceProviderAdvertisementStatus.STARTED:
                    logger.warning("[!] BLE advertisement stopped (possible sleep/hibernation wake). Re-starting advertisement...")
                    provider.start_advertising(adv_params)
            except Exception as adv_err:
                logger.warning(f"Error checking/re-starting advertisement: {adv_err}")

            packet, gpu_t, cpu_t, gpu_u, cpu_u, pwr, ram_u, ram_tot = encode_telemetry_packet()

            # Push notification to all subscribed clients
            writer = streams.DataWriter()
            writer.write_bytes(packet)
            notify_buf = writer.detach_buffer()

            try:
                res = await characteristic.notify_value_async(notify_buf)
                sub_count = len(characteristic.subscribed_clients)
            except Exception as notify_err:
                logger.warning(f"Notification error: {notify_err}")
                sub_count = 0

            now_ist = datetime.now(IST).strftime("%H:%M:%S")
            logger.info(
                f"[{now_ist} IST] 1Hz GATT Stream ({sub_count} watch subscriber(s)): "
                f"GPU {gpu_t}°C ({gpu_u}%) {pwr}W | "
                f"CPU {cpu_t}°C ({cpu_u}%) | RAM {ram_u // 1024}G/{ram_tot // 1024}G"
            )

            await asyncio.sleep(1.0)
    except asyncio.CancelledError:
        pass
    finally:
        logger.info("Stopping GATT advertising...")
        provider.stop_advertising()

if __name__ == "__main__":
    check_and_request_admin()
    try:
        asyncio.run(run_gatt_server())
    except KeyboardInterrupt:
        logger.info("BLE GATT Server stopped by user.")
