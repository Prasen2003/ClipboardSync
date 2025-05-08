import threading
import socket
import pyperclip
import os
import sys
import time
from fastapi import FastAPI, Request
from fastapi.middleware.cors import CORSMiddleware
import uvicorn
from pystray import Icon, MenuItem, Menu
from PIL import Image, ImageDraw
from zeroconf import Zeroconf, ServiceInfo

# === Globals ===
zeroconf = Zeroconf()
info = None
tray_icon = None
ip_address = None
tray_icon_active = False  # Flag to track tray icon status
last_ip = None  # To track the last IP address
ip_changed = False  # Flag to track if IP has already changed
monitor_thread = None  # Thread reference to stop the monitor when restarting
stop_monitoring = False  # Flag to stop the monitoring thread
is_restarting = False  # Flag to prevent multiple restarts during the same network change
restart_cooldown = 5  # Cooldown time in seconds to prevent rapid restarts

# === FastAPI server ===
app = FastAPI()

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)

@app.api_route("/clipboard", methods=["GET", "POST", "PUT", "DELETE"])
async def debug_clipboard(request: Request):
    print(f"📡 Received method: {request.method}")
    if request.method == "POST":
        form = await request.form()
        clipboard = form.get("clipboard", "")
        print(f"📋 Received clipboard: {clipboard}")
        pyperclip.copy(clipboard)
        return {"status": "received", "clipboard": clipboard}
    elif request.method == "GET":
        clipboard = pyperclip.paste()
        print(f"📤 Sending clipboard: {clipboard}")
        return {"status": "sent", "clipboard": clipboard}
    else:
        return {"detail": "Only GET and POST supported"}

# === Start FastAPI server ===
def start_server():
    uvicorn.run(app, host="0.0.0.0", port=8000)

# === Get local IP address ===
def get_ip():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
    except Exception:
        ip = "127.0.0.1"
    finally:
        s.close()
    return ip

# === Register service via Zeroconf ===
def register_service():
    global info
    ip = get_ip()
    desc = {"info": "Clipboard Sync Server"}
    info = ServiceInfo(
        "_http._tcp.local.",
        "ClipboardSyncServer._http._tcp.local.",
        addresses=[socket.inet_aton(ip)],
        port=8000,
        properties=desc,
        server=f"{socket.gethostname()}.local."
    )
    zeroconf.register_service(info)
    print(f"📣 Zeroconf service registered: {ip}:8000")
    return ip

def unregister_service():
    global info
    if info:
        try:
            zeroconf.unregister_service(info)
            print("🛑 Zeroconf service unregistered.")
        except Exception as e:
            print(f"⚠️ Error unregistering service: {e}")
        info = None

# === Tray icon creation ===
def create_icon():
    icon_image = Image.new("RGBA", (64, 64), (255, 255, 255, 0))
    draw = ImageDraw.Draw(icon_image)
    draw.rectangle((0, 0, 64, 64), fill=(0, 0, 255))
    draw.text((18, 20), "CB", fill="white")
    return icon_image

# === Tray Menu Callbacks ===
def on_quit(icon, item):
    unregister_service()
    icon.stop()

def on_restart(icon, item):
    unregister_service()
    icon.stop()
    print("🔄 Restarting app...")
    os.execl(sys.executable, sys.executable, *sys.argv)

# === Tray Menu ===
def create_tray_menu(ip=None):
    ip = ip or get_ip()
    return Menu(
        MenuItem("Restart", on_restart),
        MenuItem("Quit", on_quit),
        MenuItem(f"IP Address: {ip}", lambda *_: None, enabled=False)
    )

# === Restart application when IP changes ===
def restart_app():
    global is_restarting, tray_icon_active
    if not is_restarting:
        is_restarting = True
        print("🌐 IP changed, restarting the app...")
        
        # Stop the existing tray icon if it's active
        if tray_icon_active:
            tray_icon.stop()
            tray_icon_active = False  # Mark tray icon as not active
        
        unregister_service()
        time.sleep(restart_cooldown)  # Cooldown to prevent multiple restarts
        
        # Restart the app
        os.execl(sys.executable, sys.executable, *sys.argv)
    else:
        print("🛑 App is already restarting. Ignoring IP change.")

# === Monitor IP change and auto-restart ===
def monitor_ip_change():
    global ip_address, last_ip, ip_changed, stop_monitoring, is_restarting
    debounce_time = 3  # Seconds to wait before allowing another check
    
    # Skip initial restart to prevent false loop
    ip_initialized = False
    
    while True:
        if stop_monitoring:
            print("🛑 Stopping IP monitoring thread...")
            break  # Exit the loop if the flag is set
        
        time.sleep(1)
        current_ip = get_ip()
        
        # Skip the restart logic if IP is None or hasn't been initialized yet
        if not ip_initialized and current_ip != "127.0.0.1":
            ip_initialized = True
            last_ip = current_ip
            print(f"🌐 Initial IP detected: {current_ip}. No restart triggered yet.")
            continue
        
        # Only restart the app if the IP has changed and is different from the last known IP
        if current_ip != last_ip and not is_restarting:
            ip_changed = True
            print(f"🌐 IP changed from {last_ip} ➡ {current_ip}. Restarting app...")
            last_ip = current_ip  # Update the last known IP before restarting
            restart_app()  # Restart the app with the new IP
        elif current_ip == last_ip:
            ip_changed = False  # Reset flag if the IP is the same
        time.sleep(debounce_time)  # Add a small delay to avoid too frequent IP checks

# === Main ===
if __name__ == "__main__":
    # Start FastAPI server in background
    threading.Thread(target=start_server, daemon=True).start()

    # Register Zeroconf service
    ip_address = register_service()

    # Start IP monitor thread
    monitor_thread = threading.Thread(target=monitor_ip_change, daemon=True)
    monitor_thread.start()

    # Start Tray icon initially
    tray_icon = Icon("ClipboardSync", create_icon(), menu=create_tray_menu(ip_address))
    
    # Check if tray icon is already active, stop it if necessary
    if tray_icon_active:
        tray_icon.stop()
    
    tray_thread = threading.Thread(target=tray_icon.run, daemon=True)
    tray_thread.start()
    
    # Mark tray icon as active
    tray_icon_active = True

    # Keep the main thread alive to keep the tray icon running
    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        print("🔴 Application terminated.")
