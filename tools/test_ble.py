import sys
import time
import struct
import traceback

print("Testing Windows WinRT BLE Advertising...")

try:
    import winrt.windows.devices.bluetooth.advertisement as adv
    import winrt.windows.storage.streams as streams
    print("[1] WinRT modules loaded successfully.")
except Exception as e:
    print(f"[!] WinRT import failed: {e}")
    sys.exit(1)

publisher = adv.BluetoothLEAdvertisementPublisher()
print(f"[2] Initial Publisher status: {publisher.status}")

# Create sample 16-byte telemetry payload
COMPANY_ID = 0x05F9
sample_payload = struct.pack("<BBBBHHHHBBBB", 65, 50, 45, 20, 3500, 8192, 12000, 16384, 120, 16, 12, 1)

writer = streams.DataWriter()
writer.write_bytes(sample_payload)
mfg = adv.BluetoothLEManufacturerData(COMPANY_ID, writer.detach_buffer())
publisher.advertisement.manufacturer_data.append(mfg)

import uuid
SERVICE_UUID = "0000ffe0-0000-1000-8000-00805f9b34fb"
publisher.advertisement.service_uuids.append(uuid.UUID(SERVICE_UUID))
print(f"[3] Added service UUID {SERVICE_UUID} AND manufacturer data to advertisement.")

def status_changed(sender, args):
    print(f"[*] Publisher Status Changed: status={args.status}, error={args.error}")

publisher.add_status_changed(status_changed)

try:
    publisher.start()
    print(f"[4] publisher.start() called. Current status: {publisher.status}")
except Exception as e:
    print(f"[!] publisher.start() error: {e}")
    traceback.print_exc()

time.sleep(2)

print("\n[5] Now testing in-place update of manufacturer data while STARTED...")
try:
    new_writer = streams.DataWriter()
    new_writer.write_bytes(sample_payload)
    new_mfg = adv.BluetoothLEManufacturerData(COMPANY_ID, new_writer.detach_buffer())
    
    # Try updating
    publisher.advertisement.manufacturer_data.clear()
    publisher.advertisement.manufacturer_data.append(new_mfg)
    print("[+] In-place update succeeded without exception!")
except Exception as e:
    print(f"[!] In-place update failed with exception: {type(e).__name__}: {e}")

time.sleep(1)
print(f"[6] Publisher final status: {publisher.status}")
publisher.stop()
print("[7] Stopped publisher.")
