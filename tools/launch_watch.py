#!/usr/bin/env python3
"""
Galaxy Watch 4 - ADB Launcher & Live Refresh Tool
=================================================
Automates:
1. Dynamic PC IP detection (e.g. 192.168.1.5)
2. Watch IP resolution (via server API stats, active ADB devices, or ARP table)
3. ADB connection check & Wear OS dynamic wireless debugging port handling
4. Chrome browser clean restart with cache-busting to immediately refresh the dashboard
"""

import sys
import os
import re
import time
import socket
import subprocess
import urllib.request
import json
import argparse

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
PROJECT_ROOT = os.path.dirname(SCRIPT_DIR)

def get_adb_path():
    """Locate adb.exe from bundled tools or system PATH."""
    bundled = os.path.join(SCRIPT_DIR, "platform-tools", "adb.exe")
    if os.path.isfile(bundled):
        return bundled
    alt_bundled = os.path.join(PROJECT_ROOT, "tools", "platform-tools", "adb.exe")
    if os.path.isfile(alt_bundled):
        return alt_bundled
    # Check PATH
    import shutil
    system_adb = shutil.which("adb")
    if system_adb:
        return system_adb
    return None

def run_adb(adb_cmd, args, timeout=10):
    """Run an adb command and return stdout string."""
    cmd = [adb_cmd] + args
    try:
        res = subprocess.run(
            cmd,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            timeout=timeout,
            creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
        )
        return res.stdout.strip(), res.stderr.strip(), res.returncode
    except Exception as e:
        return "", str(e), 1

def get_pc_lan_ip():
    """Detect the active LAN IPv4 address of this host machine."""
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return "192.168.1.5"

def resolve_watch_ip(adb_cmd):
    """
    Resolve Galaxy Watch 4 IP address through multi-source detection:
    1. Query local server /api/stats (last_client_ip)
    2. Check currently connected adb devices
    3. Check Windows ARP table for watch MAC or 192.168.1.15
    """
    # 1. Query server.py /api/stats
    try:
        req = urllib.request.Request("http://127.0.0.1:8080/api/stats", headers={"User-Agent": "WatchLauncher"})
        with urllib.request.urlopen(req, timeout=1.0) as resp:
            data = json.loads(resp.read().decode("utf-8"))
            last_client = data.get("host", {}).get("last_client_ip")
            if last_client and last_client not in ("127.0.0.1", "::1"):
                print(f"[*] Detected Watch IP from active server telemetry: {last_client}")
                return last_client
    except Exception:
        pass

    # 2. Check adb devices for an IP-based connection
    stdout, _, _ = run_adb(adb_cmd, ["devices"])
    for line in stdout.splitlines():
        if "\tdevice" in line:
            dev_id = line.split("\t")[0].strip()
            if ":" in dev_id:
                ip_part = dev_id.split(":")[0]
                if re.match(r"^\d+\.\d+\.\d+\.\d+$", ip_part):
                    print(f"[*] Detected Watch IP from active ADB session: {ip_part}")
                    return ip_part

    # 3. Check Windows ARP table
    try:
        arp_out = subprocess.check_output(["arp", "-a"], text=True)
        # Look for 192.168.1.15 specifically or known MAC prefix
        for line in arp_out.splitlines():
            if "192.168.1.15" in line:
                print("[*] Detected Watch IP in ARP table: 192.168.1.15")
                return "192.168.1.15"
    except Exception:
        pass

    # Default fallback
    return "192.168.1.15"

def get_connected_device(adb_cmd):
    """Check if any ADB device is currently attached and authorized."""
    stdout, _, _ = run_adb(adb_cmd, ["devices"])
    lines = stdout.splitlines()
    for line in lines[1:]:
        line = line.strip()
        if not line:
            continue
        parts = line.split("\t")
        if len(parts) >= 2 and parts[1] == "device":
            return parts[0]
    return None

def ensure_adb_connection(adb_cmd, watch_ip, non_interactive=False):
    """Ensure watch is connected to ADB. Prompts for dynamic port if needed."""
    # Check if already connected
    dev = get_connected_device(adb_cmd)
    if dev:
        print(f"[OK] Watch already connected via ADB: {dev}")
        return dev

    print(f"[*] Checking standard ADB wireless port (5555) on {watch_ip}...")
    run_adb(adb_cmd, ["connect", f"{watch_ip}:5555"], timeout=3)
    dev = get_connected_device(adb_cmd)
    if dev:
        print(f"[OK] Connected to watch via standard port: {dev}")
        return dev

    # Wear OS dynamic wireless debugging prompt
    if non_interactive:
        print("[!] No active ADB device found and non-interactive mode is enabled.")
        return None

    print("\n" + "=" * 64)
    print(" [!] GALAXY WATCH 4 - WIRELESS DEBUGGING PORT REQUIRED")
    print("=" * 64)
    print(f" Galaxy Watch 4 (Wear OS) uses dynamic Wi-Fi debugging ports.")
    print(f" Watch IP: {watch_ip}")
    print()
    print(" On your Galaxy Watch 4:")
    print("   1. Open: Settings -> Developer Options -> Wireless debugging")
    print("   2. If it is already ON, toggle it OFF and turn it back ON")
    print("   3. Note the port number shown under 'IP address & Port'")
    print("      (e.g., if it shows 192.168.1.15:41235, the port is 41235)")
    print("=" * 64)

    try:
        user_input = input(f"\nEnter port number for {watch_ip} (or press Enter to skip): ").strip()
    except (EOFError, KeyboardInterrupt):
        return None

    if not user_input:
        return None

    # Handle if user pasted the full ip:port
    if ":" in user_input:
        target_addr = user_input
    else:
        port = re.sub(r"[^\d]", "", user_input)
        if not port:
            print("[ERROR] Invalid port entered.")
            return None
        target_addr = f"{watch_ip}:{port}"

    print(f"[*] Connecting to {target_addr}...")
    out, err, _ = run_adb(adb_cmd, ["connect", target_addr], timeout=5)
    print(f"    ADB Response: {out or err}")

    time.sleep(1)
    dev = get_connected_device(adb_cmd)
    if dev:
        print(f"[SUCCESS] Connected to Galaxy Watch 4 ({dev})!")
        return dev
    else:
        print("[!] Connection was not successful. Ensure watch display is awake and prompt accepted.")
        return None

def launch_on_watch(adb_cmd, pc_ip, port=8080):
    """Wake screen, force-kill existing browser to bust cache, and launch URL."""
    url = f"http://{pc_ip}:{port}"
    print(f"\n[*] Preparing to launch on watch: {url}")

    # 1. Wake up watch display
    run_adb(adb_cmd, ["shell", "input", "keyevent", "KEYCODE_WAKEUP"])

    # 2. Dismiss keyguard if needed
    run_adb(adb_cmd, ["shell", "wm", "dismiss-keyguard"])

    # 3. Force stop Chrome or Samsung Internet to bust in-memory tab state
    print("[*] Closing running browser session to force fresh reload...")
    run_adb(adb_cmd, ["shell", "am", "force-stop", "com.android.chrome"])
    run_adb(adb_cmd, ["shell", "am", "force-stop", "com.sec.android.app.sbrowser"])
    time.sleep(0.5)

    # 4. Launch browser intent
    print(f"[*] Opening dashboard at {url}...")
    out, err, code = run_adb(adb_cmd, [
        "shell", "am", "start",
        "-a", "android.intent.action.VIEW",
        "-d", url
    ])

    if code == 0:
        # Brief pause then simulate a tap to trigger instant fullscreen mode
        time.sleep(1.2)
        run_adb(adb_cmd, ["shell", "input", "tap", "225", "225"])

        print("\n" + "=" * 64)
        print(f" [SUCCESS] Hardware Monitor refreshed on Galaxy Watch 4!")
        print(f" Target URL : {url}")
        print("=" * 64)
        return True
    else:
        print(f"[ERROR] Failed to start intent: {err or out}")
        return False

def main():
    parser = argparse.ArgumentParser(description="Galaxy Watch 4 ADB Dashboard Launcher & Refresher")
    parser.add_argument("--ip", help="Manual Galaxy Watch IP address")
    parser.add_argument("--pc-ip", help="Manual PC Wi-Fi IP address")
    parser.add_argument("--port", type=int, default=8080, help="Server port (default: 8080)")
    parser.add_argument("--non-interactive", action="store_true", help="Do not prompt for wireless debugging port")
    args = parser.parse_args()

    adb = get_adb_path()
    if not adb:
        print("[ERROR] adb.exe not found in tools/platform-tools/ or system PATH.")
        sys.exit(1)

    pc_ip = args.pc_ip or get_pc_lan_ip()
    watch_ip = args.ip or resolve_watch_ip(adb)

    print("========================================================")
    print("   Galaxy Watch 4 - Dashboard ADB Launcher / Refresher   ")
    print("========================================================")
    print(f" [+] ADB Path  : {adb}")
    print(f" [+] PC LAN IP : {pc_ip}")
    print(f" [+] Watch IP  : {watch_ip}")
    print(f" [+] Port      : {args.port}")
    print("--------------------------------------------------------")

    # Check connection
    device = ensure_adb_connection(adb, watch_ip, non_interactive=args.non_interactive)
    if not device:
        print("\n[!] Could not establish ADB connection with the watch.")
        print("    You can still open the dashboard manually on your watch at:")
        print(f"    >>>  http://{pc_ip}:{args.port}  <<<")
        sys.exit(1)

    # Launch & Refresh
    success = launch_on_watch(adb, pc_ip, port=args.port)
    if not success:
        sys.exit(1)

if __name__ == "__main__":
    main()
