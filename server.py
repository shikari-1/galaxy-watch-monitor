import os
import sys
import time
import socket
import threading
import subprocess
import json
import logging
from flask import Flask, render_template, jsonify, Response, request

# Configure logging
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
    datefmt="%H:%M:%S"
)
logger = logging.getLogger("GalaxyWatchMonitor")

try:
    import psutil
except ImportError:
    logger.error("psutil is not installed. Run: pip install psutil")
    sys.exit(1)

app = Flask(__name__)

# Global state for latest hardware statistics
hardware_state = {
    "timestamp": time.time(),
    "cpu": {
        "name": "AMD/Intel CPU",
        "usage": 0.0,
        "cores_usage": [],
        "frequency_mhz": 0,
        "temperature": 0.0,
        "temp_source": "Dynamic Estimate",
        "power_w": 0.0
    },
    "gpu": {
        "name": "NVIDIA GPU",
        "usage": 0.0,
        "temperature": 0.0,
        "vram_used_mb": 0,
        "vram_total_mb": 0,
        "vram_percent": 0.0,
        "clock_mhz": 0,
        "power_w": 0.0,
        "fan_percent": 0,
        "available": False
    },
    "ram": {
        "used_gb": 0.0,
        "total_gb": 0.0,
        "percent": 0.0
    },
    "net": {
        "download_kbps": 0.0,
        "upload_kbps": 0.0
    },
    "host": {
        "name": socket.gethostname(),
        "local_ip": "127.0.0.1",
        "port": 8080
    }
}

state_lock = threading.Lock()

def get_active_lan_ip():
    """Detect the active LAN/Wi-Fi IPv4 address used to reach the local router."""
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        # Connect to public DNS IP (doesn't send packets, just finds routing interface)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        try:
            return socket.gethostbyname(socket.gethostname())
        except Exception:
            return "127.0.0.1"

def query_nvidia_smi():
    """Query NVIDIA GPU metrics via nvidia-smi with millisecond execution."""
    try:
        query_fields = (
            "name,temperature.gpu,utilization.gpu,utilization.memory,"
            "memory.total,memory.used,clocks.current.graphics,power.draw,fan.speed"
        )
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
            timeout=1.5,
            creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
        )
        if res.returncode == 0 and res.stdout.strip():
            lines = res.stdout.strip().split("\n")
            # Select discrete GPU (first one or RTX)
            target_line = lines[0]
            for line in lines:
                if "RTX" in line or "GeForce" in line or "NVIDIA" in line:
                    target_line = line
                    break
            
            parts = [p.strip() for p in target_line.split(",")]
            if len(parts) >= 8:
                name = parts[0]
                temp = float(parts[1]) if parts[1] != "[Not Supported]" else 0.0
                usage = float(parts[2]) if parts[2] != "[Not Supported]" else 0.0
                total_vram = float(parts[4]) if parts[4] != "[Not Supported]" else 8192.0
                used_vram = float(parts[5]) if parts[5] != "[Not Supported]" else 0.0
                clock = float(parts[6]) if parts[6] != "[Not Supported]" else 0.0
                power = float(parts[7]) if parts[7] != "[Not Supported]" else 0.0
                fan = float(parts[8]) if len(parts) > 8 and parts[8] != "[Not Supported]" else 0.0
                vram_pct = round((used_vram / total_vram) * 100, 1) if total_vram > 0 else 0.0

                return {
                    "name": name,
                    "usage": usage,
                    "temperature": temp,
                    "vram_used_mb": round(used_vram),
                    "vram_total_mb": round(total_vram),
                    "vram_percent": vram_pct,
                    "clock_mhz": round(clock),
                    "power_w": round(power, 1),
                    "fan_percent": round(fan),
                    "available": True
                }
    except Exception as e:
        logger.debug(f"NVIDIA query failed: {e}")
    
    return {
        "name": "NVIDIA GPU",
        "usage": 0.0,
        "temperature": 0.0,
        "vram_used_mb": 0,
        "vram_total_mb": 0,
        "vram_percent": 0.0,
        "clock_mhz": 0,
        "power_w": 0.0,
        "fan_percent": 0,
        "available": False
    }

def get_cpu_temperature(cpu_usage, cpu_freq):
    """
    Retrieve CPU temperature using WMI (LibreHardwareMonitor / OpenHardwareMonitor),
    or calculate a responsive dynamic thermal model based on current utilization.
    """
    # 1. Try LibreHardwareMonitor or OpenHardwareMonitor WMI if user runs them
    try:
        import wmi
        w = wmi.WMI(namespace="root\\LibreHardwareMonitor")
        sensors = w.Sensor()
        for s in sensors:
            if s.SensorType == "Temperature" and ("CPU Package" in s.Name or "Core" in s.Name):
                return float(s.Value), "Hardware Sensor (LHM)"
    except Exception:
        pass

    try:
        import wmi
        w = wmi.WMI(namespace="root\\OpenHardwareMonitor")
        sensors = w.Sensor()
        for s in sensors:
            if s.SensorType == "Temperature" and ("CPU Package" in s.Name or "Core" in s.Name):
                return float(s.Value), "Hardware Sensor (OHM)"
    except Exception:
        pass

    # 2. Dynamic thermal estimation:
    # Modern desktop CPU idle: ~38°C, max full load: ~78°C
    base_idle = 38.0
    load_factor = (cpu_usage / 100.0) * 38.0
    freq_boost = 0.0
    if cpu_freq and cpu_freq.max and cpu_freq.max > 0:
        freq_ratio = cpu_freq.current / cpu_freq.max
        freq_boost = max(0.0, (freq_ratio - 0.7) * 8.0)
    
    estimated_temp = round(base_idle + load_factor + freq_boost, 1)
    return estimated_temp, "Dynamic Estimate"

def hardware_monitor_worker():
    """Background polling thread that updates hardware stats continuously."""
    global hardware_state
    
    # Initialize CPU measurement
    psutil.cpu_percent(interval=None)
    prev_net = psutil.net_io_counters()
    prev_time = time.time()
    
    local_ip = get_active_lan_ip()
    host_name = socket.gethostname()
    
    logger.info("Hardware monitor collector thread started.")
    
    while True:
        try:
            curr_time = time.time()
            elapsed_time = max(0.2, curr_time - prev_time)
            
            # CPU
            cpu_usage = psutil.cpu_percent(interval=None)
            cores_usage = psutil.cpu_percent(interval=None, percpu=True)
            cpu_freq = psutil.cpu_freq()
            freq_mhz = round(cpu_freq.current) if cpu_freq else 0
            cpu_temp, temp_source = get_cpu_temperature(cpu_usage, cpu_freq)
            
            # RAM
            vm = psutil.virtual_memory()
            used_gb = round(vm.used / (1024 ** 3), 1)
            total_gb = round(vm.total / (1024 ** 3), 1)
            ram_pct = vm.percent
            
            # Network rate
            curr_net = psutil.net_io_counters()
            down_kbps = round(((curr_net.bytes_recv - prev_net.bytes_recv) / elapsed_time) / 1024.0, 1)
            up_kbps = round(((curr_net.bytes_sent - prev_net.bytes_sent) / elapsed_time) / 1024.0, 1)
            prev_net = curr_net
            prev_time = curr_time
            
            # GPU
            gpu_data = query_nvidia_smi()
            
            with state_lock:
                hardware_state["timestamp"] = curr_time
                hardware_state["cpu"] = {
                    "name": "AMD/Intel CPU",
                    "usage": cpu_usage,
                    "cores_usage": cores_usage,
                    "frequency_mhz": freq_mhz,
                    "temperature": cpu_temp,
                    "temp_source": temp_source,
                    "power_w": round(15.0 + (cpu_usage / 100.0) * 65.0, 1)
                }
                hardware_state["gpu"] = gpu_data
                hardware_state["ram"] = {
                    "used_gb": used_gb,
                    "total_gb": total_gb,
                    "percent": ram_pct
                }
                hardware_state["net"] = {
                    "download_kbps": down_kbps,
                    "upload_kbps": up_kbps
                }
                hardware_state["host"]["local_ip"] = local_ip
                hardware_state["host"]["name"] = host_name
                
        except Exception as e:
            logger.error(f"Error updating hardware metrics: {e}")
            
        time.sleep(1.0)

# Routes
@app.route("/")
def index():
    """Serves the main hardware dashboard."""
    with state_lock:
        ip = hardware_state["host"]["local_ip"]
        port = hardware_state["host"]["port"]
    return render_template("index.html", local_ip=ip, port=port, hostname=socket.gethostname())

@app.route("/api/stats")
def get_stats():
    """REST endpoint returning immediate JSON snapshot of all hardware telemetry."""
    with state_lock:
        return jsonify(hardware_state)

@app.route("/api/stream")
def sse_stream():
    """Server-Sent Events (SSE) stream delivering real-time telemetry every second."""
    def event_stream():
        last_sent_time = 0
        while True:
            with state_lock:
                curr_ts = hardware_state["timestamp"]
                payload = json.dumps(hardware_state)
            
            if curr_ts != last_sent_time:
                last_sent_time = curr_ts
                yield f"data: {payload}\n\n"
            time.sleep(0.5)

    response = Response(event_stream(), mimetype="text/event-stream")
    response.headers["Cache-Control"] = "no-cache"
    response.headers["X-Accel-Buffering"] = "no"
    response.headers["Connection"] = "keep-alive"
    return response

def print_banner(ip, port, hostname):
    banner = f"""
========================================================================
           GALAXY WATCH 4 - HARDWARE MONITOR DESKTOP SERVER            
========================================================================

  [+] Server Status : Running Active Telemetry
  [+] Local Wi-Fi IP: {ip}
  [+] Hostname      : {hostname}.local
  [+] Port          : {port}

  ----------------------------------------------------------------------
  OPEN ON YOUR GALAXY WATCH 4:
  1. Ensure watch is connected to the same Wi-Fi.
  2. Open Samsung Internet (or WristNet) browser on your watch.
  3. Enter this URL:
     
     >>>   http://{ip}:{port}   <<<
     
  4. Tip: Tap the Star icon to bookmark it!
  5. Place watch on magnetic desk charger to keep display active.

  ----------------------------------------------------------------------
  OPEN ON YOUR PC / PHONE:
     >>>   http://localhost:{port}
     >>>   http://{ip}:{port}
========================================================================
"""
    print(banner)

def start_server(port=8080):
    hardware_state["host"]["port"] = port
    ip = get_active_lan_ip()
    hostname = socket.gethostname()
    
    # Start hardware polling in background daemon thread
    worker = threading.Thread(target=hardware_monitor_worker, daemon=True)
    worker.start()
    
    print_banner(ip, port, hostname)
    
    # Run Flask server accessible on all network interfaces
    app.run(host="0.0.0.0", port=port, debug=False, threaded=True)

if __name__ == "__main__":
    port = 8080
    if len(sys.argv) > 1:
        try:
            port = int(sys.argv[1])
        except ValueError:
            pass
    start_server(port=port)
