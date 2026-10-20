# ⌚ Galaxy Watch 4 — Desktop Hardware Monitor

Turn your **Samsung Galaxy Watch 4** (or any Wear OS watch like GW5, GW6, GW7, Pixel Watch) into a futuristic mini desktop hardware monitor resting on your desk or inside your PC case.

Displays real-time **CPU & GPU temperatures, utilization %, clocks, VRAM, RAM, and power draw** over local Wi-Fi with ultra-low latency and zero battery strain.

---

## ✨ Features

- **Designed for Circular Wear OS Screens**: Optimized for 396×396 (40mm) and 450×450 (44mm/Classic) displays.
- **Pure AMOLED Black (`#000000`)**: Inactive pixels are completely powered off, keeping the watch cool on its magnetic charger and conserving battery.
- **🛡️ Built-in OLED Burn-In Protection**: Subtle 8-point orbital pixel shifting every 90 seconds prevents static image retention without being noticeable.
- **5 Switchable Watch Face Styles**:
  1. **Cyber Ring (Default)**: Dual circular neon arcs with clock, power, and RAM indicators.
  2. **Dual Arc HUD**: Speedometer-style dual arc gauges inspired by automotive telemetry.
  3. **Minimal OLED**: Ultra-clean, high-contrast digital display for maximum efficiency.
  4. **Stat Grid**: Dense 4-quadrant telemetry dashboard.
  5. **Chrono Cockpit**: 60-tick tachometer racing face with live GPU Fan RPM %, GPU Clock MHz, and cockpit gauge arcs.
- **🎨 5 Curated AMOLED Color Accent Themes**:
  - **Cyberpunk Neon**: Electric Cyan & Coral Crimson.
  - **Toxic Matrix**: Emerald Green & Acid Lime.
  - **Solar Flare**: Amber Gold & Sunset Crimson.
  - **Synthwave Outrun**: Hot Neon Pink & Electric Violet.
  - **Stealth Ice**: Crisp Pure White & Arctic Ice Blue.
- **🔥 Dynamic Thermal Alert Thresholds**:
  - Real-time thermal detection for both NVIDIA GPU & CPU.
  - Configurable Warning (`75°C` Amber Glow) and Critical Overheat (`83°C` Pulsing Crimson & Animated Flame Badge).
  - Configurable via companion sidebar sliders and saved in `localStorage`.
- **Real-Time Telemetry (1 Hz / 1000ms)**:
  - **GPU**: NVIDIA RTX 3060 Ti / GeForce temperature, utilization %, VRAM used/total, clock frequency, power draw, and fan speed % (via official `nvidia-smi`).
  - **CPU**: Utilization %, core frequencies, power, and package temperature.
  - **RAM & Network**: Active system memory and download/upload throughput.
- **Zero Cloud & 100% Private**: Runs strictly on your local Wi-Fi router. No accounts, no credentials, no tracking.
- **Interactive Gestures on Watch**:
  - **Tap screen**: Cycle between 5 watch faces.
  - **Double-tap screen**: Enter/exit native borderless fullscreen.
  - **Long-press / Hold**: Cycle through color accent themes with on-screen HUD feedback.
  - **Screen WakeLock**: Uses HTML5 Wake Lock API to prevent the display from timing out.
- **Desktop Companion View**: Opening the page on your PC browser displays a live Galaxy Watch simulator with a bezel frame, QR code for phone scanning, theme controls, and hardware telemetry.

---

## 🚀 Quick Start

### 1. Run on Your PC
Make sure Python 3.9+ is installed.

#### Option A: 1-Click Launch (Windows)
Double-click `run.bat` in this folder.

#### Option B: Manual Command
```bash
# Install dependencies
pip install -r requirements.txt

# Start server on default port 8080
python server.py 8080
```

The terminal will print your active LAN IP address:
```text
========================================================================
           GALAXY WATCH 4 - HARDWARE MONITOR DESKTOP SERVER            
========================================================================
  [+] Local Wi-Fi IP: 192.168.1.5
  [+] Hostname      : PavilionDesktop.local
  [+] Port          : 8080

  OPEN ON YOUR GALAXY WATCH 4:
  >>>   http://192.168.1.5:8080   <<<
========================================================================
```

---

## ⌚ Setting Up Your Galaxy Watch 4

### Step 1: Connect to Wi-Fi
Ensure your Galaxy Watch 4 is connected to the **same Wi-Fi network** as your PC.

### Step 2: Open the Dashboard in Watch Browser
1. Open **Samsung Internet for Galaxy Watch** (available for free on Google Play on your watch) or **WristNet**.
2. Type in your PC's IP address:
   ```text
   http://<YOUR-PC-IP>:8080
   ```
   *(e.g., `http://192.168.1.5:8080`)*
3. **One-Time Bookmark**: Tap the **Star / Bookmark icon** or "Add to Bookmarks". Now you can open it in 1 tap anytime!
4. **Fullscreen**: Double-tap anywhere on the watch screen to hide the browser URL bar.

---

## ⚡ Crucial Desk Display Settings

To keep your watch display active 24/7 without turning off while resting on the desk charger:

1. **Enable Developer Options on Watch**:
   - Go to **Settings** > **About watch** > **Software info**.
   - Tap **Software version** 7 times rapidly until a toast says *"Developer mode turned on"*.
2. **Toggle "Stay Awake While Charging"**:
   - Go back to **Settings** > **Developer options**.
   - Enable **Stay awake while charging**.
3. **Desk Setup**:
   - Place your watch on its magnetic charging puck or desk stand.
   - The screen will remain illuminated with live temperatures and usage metrics!

---

## 🛠️ Pushing to GitHub

To publish this project to your GitHub account:

```bash
# Initialize git repository
git init

# Add all files
git add .

# Create initial commit
git commit -m "feat: initial commit of Galaxy Watch 4 hardware monitor"

# Rename branch to main
git branch -M main

# Add your GitHub remote repository (replace with your repo URL)
git remote add origin https://github.com/<your-username>/galaxy-watch-monitor.git

# Push to GitHub
git push -u origin main
```

---

## 📄 License
MIT License. Free to use, modify, and distribute.
