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

# === Monitor IP change and auto-restart ===
def monitor_ip_change(original_ip):
    while True:
        time.sleep(5)
        current_ip = get_ip()
        if current_ip != original_ip:
            print(f"🌐 IP changed from {original_ip} ➡ {current_ip}. Restarting...")
            unregister_service()
            os.execl(sys.executable, sys.executable, *sys.argv)

# === Main ===
if __name__ == "__main__":
    # Start FastAPI server in background
    threading.Thread(target=start_server, daemon=True).start()

    # Register Zeroconf service
    ip_address = register_service()

    # Start IP monitor thread
    threading.Thread(target=monitor_ip_change, args=(ip_address,), daemon=True).start()

    # Create and run tray icon
    tray_icon = Icon("ClipboardSync", create_icon(), menu=create_tray_menu(ip_address))
    tray_icon.run()
