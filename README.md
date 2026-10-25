# Galaxy Watch 4 - Desktop Hardware Monitor

Turn your **Samsung Galaxy Watch 4** (or any Wear OS device like Galaxy Watch 5, 6, 7, Ultra, or Pixel Watch) into a dedicated, real-time desktop hardware telemetry display resting on your desk or charging stand.

Displays live **GPU & CPU temperatures, compute utilization, dedicated VRAM, system RAM, and power draw** over **pure Bluetooth Low Energy (BLE)** at 1.0s (1Hz) intervals with zero Wi-Fi dependency and zero battery strain.

---

## Architecture Overview

The project provides two independent companion interfaces:

```
                      PC Desktop Sensors
               (nvidia-smi + Windows Kernel)
                             │
            ┌────────────────┴────────────────┐
            ▼                                 ▼
   [Native Wear OS App]             [Web Companion]
   Bluetooth Low Energy (1Hz)       Local Wi-Fi (HTTP/WS)
   • 16-Byte Binary Broadcast       • Fullscreen Browser PWA
   • Zero Wi-Fi Dependency          • 5 Switchable Watch Faces
   • Full-Color AMOLED Display      • Interactive Touch Controls
   • Dual Arc Speedometer HUD       • Orbital Burn-In Shifting
```

---

## 1. Native Wear OS Application (`wear-app/`)

A high-performance, native Android application built with **Jetpack Compose for Wear OS** and designed specifically for round 450×450 (44mm) and 396×396 (40mm) AMOLED smartwatch screens.

### Key Features
- **Dual Arc Speedometer HUD (`faceArc`)**:
  - Two side-by-side circular speedometer dials ($140^\circ \to 40^\circ$ sweep) with thermal gradient strokes (`#ffffff` $\to$ `#ffb020` $\to$ `#ff5500` $\to$ `#ff0022`).
  - Bold `GPU` and `CPU` temperature readouts with degree symbols.
  - Mathematically 1:1 equilateral circular geometry with zero lateral distortion.
- **Top Breathing 24-Hour Digital Clock with Seconds**:
  - Displays real-time synchronized PC local time in **`HH:MM:SS` (24h IST)**.
  - Smooth continuous breathing animation (`0.60` $\to$ `1.00` opacity) without harsh state jumps.
- **Full-Track Memory Progress Bars**:
  - Horizontal progress tracks for **VRAM** (`1.8 / 8.0G`) and **System RAM** (`12.1 / 15.3G (78%)`).
  - Gradient anchored across the full 100% track width: low loads (0%–55%) remain crisp pure white, transitioning to Amber ($>55\%$) and Red ($>90\%$) only under heavy allocation.
- **Zero Wi-Fi Battery Drain**:
  - Listens purely on the Bluetooth Low Energy radio for Company ID `0x05F9` manufacturer beacons.
  - No active pairing, no GATT connection handshakes, and no Wi-Fi radio wakeups.
- **AMOLED Keep-Alive**:
  - Automatically holds full-color AMOLED illumination while docked on its magnetic desk charger via `FLAG_KEEP_SCREEN_ON` and `ChargerReceiver`.

---

## 2. Desktop BLE Telemetry Server (`wear-app/desktop-server/`)

A lightweight Python daemon that polls system hardware sensors and broadcasts a compact 16-byte binary payload every 1.0 second over Windows Bluetooth Low Energy.

### Packet Structure (`<BBBBHHHHBBBB` Little-Endian):
```
[0]     gpuTemp      (uint8)  : NVIDIA core temperature (°C)
[1]     cpuTemp      (uint8)  : CPU package thermal reading (°C)
[2]     gpuUsage     (uint8)  : GPU core compute utilization (0-100%)
[3]     cpuUsage     (uint8)  : CPU overall utilization (0-100%)
[4..5]  vramUsedMb   (uint16) : Dedicated VRAM currently allocated (MB)
[6..7]  vramTotalMb  (uint16) : Physical VRAM capacity (e.g. 8192 MB)
[8..9]  ramUsedMb    (uint16) : Host committed physical memory (MB)
[10..11]ramTotalMb   (uint16) : Total physical host memory (MB)
[12]    gpuPowerW    (uint8)  : Instantaneous GPU board power draw (Watts)
[13]    istHour      (uint8)  : PC 24-Hour local clock hour
[14]    istMinute    (uint8)  : PC local clock minute
[15]    istSecond    (uint8)  : PC local clock second (live 1Hz tick)
```

- **Fit for 31-Byte BLE Frame**: Total advertising payload is only 21 bytes (3B flags + 2B Company ID + 16B telemetry), fitting within Bluetooth legacy specifications and broadcasting with Windows WinRT.

### Running the Desktop BLE Server:
```bash
# Option A: 1-Click Launch (Windows)
wear-app/run_ble_server.bat

# Option B: Manual Command
cd wear-app/desktop-server
python ble_server.py
```

---

## 3. Web Companion (`web-server/`)

For environments without Bluetooth, an alternative Flask-based web companion is included in [`web-server/`](./web-server/):

- Serves a responsive browser dashboard over local Wi-Fi.
- Offers 5 switchable visual themes: **Cyber Ring**, **Dual Arc HUD**, **Minimal OLED**, **Stat Grid**, and **Chrono Cockpit**.
- Includes 8-point orbital pixel shifting to protect OLED displays against static retention.
- Double-tap screen toggles native borderless fullscreen.

### Running the Web Server:
```bash
cd web-server
run.bat
```

---

## 4. Automated Cloud Compilation (CI/CD)

The native Wear OS application is compiled using GitHub Actions:

- [`.github/workflows/build-wear-app.yml`](./.github/workflows/build-wear-app.yml) runs on `ubuntu-latest` with Java 17 and Gradle 8.7 auto-provisioning.
- Every push to `wear-app/**` triggers an automated clean build, producing a signed `app-debug.apk` artifact ready for sideloading.

---

## License

MIT License. Free to use, modify, and distribute.
