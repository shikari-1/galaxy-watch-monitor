# Galaxy Watch 4 - Dedicated Wear OS App

Native Android (Wear OS 3+) application built with **Jetpack Compose for Wear OS** and **Bluetooth Low Energy (BLE)**.

Designed to keep your Galaxy Watch 4 cool, power-efficient, and running at **1-second (1Hz) telemetry rates** 24/7 without overheating or dropping to monochrome.

---

## Highlights

1. **1-Second Update Rate (1Hz)**:
   - Receives a 16-byte raw binary packet over Bluetooth Low Energy.
   - Uses less than 1ms of radio/CPU time per second, eliminating the browser heat issues.
2. **Full-Color AMOLED (No Monochrome Drop)**:
   - Keeps the full Cyberpunk Cyan and Coral dual speedometer arcs, progressive temperature color shifts, and breathing IST clock in vivid color while resting on the charger.
   - Screen stays active via `FLAG_KEEP_SCREEN_ON`.
3. **Breathing 24-Hour IST Clock**:
   - Pulses smoothly at the top of the display (`22:30`).
   - Automatically pauses breathing and dims digits if the PC stops transmitting for more than 2.5 seconds.
4. **Auto-Launch on Desk Charger**:
   - `ChargerReceiver` detects when the watch touches the magnetic charger puck and launches the dashboard automatically.
5. **Zero Wi-Fi Required**:
   - Operates over Bluetooth while keeping your PC Bluetooth available for your headphones, mouse, and game controllers.

---

## Project Structure

```text
wear-app/
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       └── java/com/galaxywatch/monitor/
│           ├── MainActivity.kt        # Full-color UI + Screen Wake Lock
│           ├── data/
│           │   └── HardwareTelemetry.kt # 16-byte decoded data model
│           ├── ble/
│           │   └── BleClientManager.kt # Low-power BLE GATT receiver
│           ├── ui/
│           │   ├── Theme.kt           # AMOLED Black & Progressive Colors
│           │   └── DualArcHud.kt      # Native 60fps Dual Speedometer
│           └── receiver/
│               └── ChargerReceiver.kt # Auto-start on charger dock
├── build.gradle.kts
└── settings.gradle.kts
```

---

## How to Build & Deploy to Your Watch

### Step 1: Open in Android Studio
1. Open **Android Studio** (Hedgehog or newer recommended).
2. Choose **Open** and select the `wear-app` folder inside this repository.
3. Allow Gradle to sync dependencies.

### Step 2: Connect Android Studio to Your Watch
Because your watch is already configured for wireless debugging:
1. In Android Studio, open the **Device Manager** or top device dropdown.
2. If your watch is already connected via ADB (`adb devices`), it will appear directly as:
   `Samsung SM-R870`
3. Click the green **Run (▶)** button.
4. Android Studio will compile the APK and install it directly onto your Galaxy Watch 4!

---

## PC Bluetooth Server Setup

On your PC, launch the companion Bluetooth Low Energy server:

```bash
# Optional dependency for Windows BLE GATT broadcast
pip install bleak

# Start BLE server at 1Hz
python ble_server.py
```
