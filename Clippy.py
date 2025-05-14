import threading
import socket
import pyperclip
import os
import sys
import time
import json
from fastapi import FastAPI, Request
from fastapi.middleware.cors import CORSMiddleware
import uvicorn
from pystray import Icon, MenuItem, Menu
from PIL import Image, ImageDraw
from zeroconf import Zeroconf, ServiceInfo
import tkinter as tk
from tkinter import ttk
from pathlib import Path
from fastapi import FastAPI, Request, HTTPException
from fastapi.responses import JSONResponse
from fastapi.middleware.cors import CORSMiddleware
# === Globals ===
PASSWORD = "your_secure_password"
zeroconf = Zeroconf()
info = None
tray_icon = None
ip_address = None
tray_icon_active = False
last_ip = None
ip_changed = False
monitor_thread = None
stop_monitoring = False
is_restarting = False
restart_cooldown = 5
connected_clients = set()
clipboard_history = []
HISTORY_LIMIT = 20
HISTORY_FILE = Path("clipboard_history.txt")
tk_window = None
root = None  # Tkinter root

# === FastAPI server ===
app = FastAPI()

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)

# === Helper functions ===
def load_history_from_file():
    global clipboard_history
    if HISTORY_FILE.exists():
        try:
            with open(HISTORY_FILE, "r", encoding="utf-8") as f:
                clipboard_history = json.load(f)
                clipboard_history = clipboard_history[:HISTORY_LIMIT]
        except Exception as e:
            print(f"⚠️ Error loading history file: {e}")
            clipboard_history = []


def save_history_to_file():
    with open(HISTORY_FILE, "w", encoding="utf-8") as f:
        for entry in clipboard_history[:HISTORY_LIMIT]:
            f.write(entry + "\n")

def save_history_to_file():
    with open(HISTORY_FILE, "w", encoding="utf-8") as f:
        json.dump(clipboard_history[:HISTORY_LIMIT], f, ensure_ascii=False, indent=2)

def add_to_history(text):
    global clipboard_history
    if text.strip() and (not clipboard_history or clipboard_history[0] != text):
        if text in clipboard_history:
            clipboard_history.remove(text)
        clipboard_history.insert(0, text)
        if len(clipboard_history) > HISTORY_LIMIT:
            clipboard_history.pop()
        save_history_to_file()

def start_server():
    uvicorn.run(app, host="0.0.0.0", port=8000)

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

def create_icon():
    icon_image = Image.new("RGBA", (64, 64), (255, 255, 255, 0))
    draw = ImageDraw.Draw(icon_image)
    draw.rectangle((0, 0, 64, 64), fill=(0, 0, 255))
    draw.text((18, 20), "CB", fill="white")
    return icon_image

def on_quit(icon, item):
    unregister_service()
    icon.stop()
    if root:
        root.quit()

def on_restart(icon, item):
    unregister_service()
    icon.stop()
    if root:
        root.quit()
    print("🔄 Restarting app...")
    os.execl(sys.executable, sys.executable, *sys.argv)

def show_connections(icon, item):
    def show_window():
        global tk_window
        if tk_window and tk_window.winfo_exists():
            tk_window.lift()
            return

        tk_window = tk.Toplevel(root)
        tk_window.title("Connected Devices")
        tk_window.geometry("300x200")

        ttk.Label(tk_window, text="Connected IPs:", font=("Segoe UI", 12)).pack(pady=10)
        box = tk.Listbox(tk_window, font=("Segoe UI", 10))
        box.pack(expand=True, fill="both", padx=10)

        for ip in sorted(connected_clients):
            box.insert(tk.END, ip)

        ttk.Button(tk_window, text="Close", command=tk_window.destroy).pack(pady=5)

    root.after(0, show_window)

def show_clipboard_history(icon, item):
    def show_window():
        global tk_window
        if tk_window and tk_window.winfo_exists():
            tk_window.lift()
            return

        tk_window = tk.Toplevel(root)
        tk_window.title("Clipboard History")
        tk_window.geometry("400x300")

        ttk.Label(tk_window, text="Clipboard History:", font=("Segoe UI", 12)).pack(pady=10)
        box = tk.Listbox(tk_window, font=("Segoe UI", 10), selectmode=tk.SINGLE)
        box.pack(expand=True, fill="both", padx=10)

        entry_map = []

        for entry in clipboard_history:
            display = entry.replace("\n", "⏎")
            short = display if len(display) <= 100 else display[:100] + "..."
            box.insert(tk.END, short)
            entry_map.append(entry)

        # === Tooltip widget ===
        tooltip = tk.Toplevel(tk_window)
        tooltip.withdraw()
        tooltip.overrideredirect(True)
        label = tk.Label(tooltip, text="", justify="left", bg="#ffffe0", relief="solid", borderwidth=1,
                         font=("Segoe UI", 9), wraplength=350)
        label.pack()

        def show_tooltip(event):
            try:
                index = box.nearest(event.y)
                full_text = entry_map[index]
                label.config(text=full_text)
                x = event.x_root + 10
                y = event.y_root + 10
                tooltip.geometry(f"+{x}+{y}")
                tooltip.deiconify()
            except Exception:
                tooltip.withdraw()

        def hide_tooltip(event):
            tooltip.withdraw()

        box.bind("<Motion>", show_tooltip)
        box.bind("<Leave>", hide_tooltip)

        def on_copy_selected():
            selected = box.curselection()
            if selected:
                full_text = entry_map[selected[0]]
                pyperclip.copy(full_text)
                print("✅ Copied to clipboard:", repr(full_text))

        def on_double_click(event):
            on_copy_selected()

        box.bind("<Double-Button-1>", on_double_click)

        def on_clear_history():
            global clipboard_history
            clipboard_history.clear()
            save_history_to_file()
            box.delete(0, tk.END)
            entry_map.clear()
            print("🧹 Clipboard history cleared.")

        ttk.Button(tk_window, text="Copy Selected", command=on_copy_selected).pack(pady=5)
        ttk.Button(tk_window, text="Clear History", command=on_clear_history).pack(pady=5)
        ttk.Button(tk_window, text="Close", command=tk_window.destroy).pack(pady=5)

    root.after(0, show_window)

def create_tray_menu(ip=None):
    ip = ip or get_ip()
    return Menu(
        MenuItem("Show Connections", show_connections),
        MenuItem("Clipboard History", show_clipboard_history),
        MenuItem("Restart", on_restart),
        MenuItem("Quit", on_quit),
        MenuItem(f"IP Address: {ip}", lambda *_: None, enabled=False)
    )

def restart_app():
    global is_restarting, tray_icon_active
    if not is_restarting:
        is_restarting = True
        print("🌐 IP changed, restarting the app...")
        if tray_icon_active:
            tray_icon.stop()
            tray_icon_active = False
        unregister_service()
        time.sleep(restart_cooldown)
        os.execl(sys.executable, sys.executable, *sys.argv)
    else:
        print("🛑 App is already restarting. Ignoring IP change.")

def monitor_ip_change():
    global ip_address, last_ip, ip_changed, stop_monitoring, is_restarting
    debounce_time = 3
    ip_initialized = False

    while True:
        if stop_monitoring:
            print("🛑 Stopping IP monitoring thread...")
            break
        time.sleep(1)
        current_ip = get_ip()
        if not ip_initialized and current_ip != "127.0.0.1":
            ip_initialized = True
            last_ip = current_ip
            print(f"🌐 Initial IP detected: {current_ip}. No restart triggered yet.")
            continue
        if current_ip != last_ip and not is_restarting:
            ip_changed = True
            print(f"🌐 IP changed from {last_ip} ➡ {current_ip}. Restarting app...")
            last_ip = current_ip
            restart_app()
        elif current_ip == last_ip:
            ip_changed = False
        time.sleep(debounce_time)

def check_password(request: Request):
    token = request.headers.get("X-Auth-Token")
    if token != PASSWORD:
        raise HTTPException(status_code=401, detail="Unauthorized")

@app.get("/ping")
async def ping(request: Request):
    check_password(request)
    return {"status": "ok"}

@app.get("/clipboard")
async def get_clipboard(request: Request):
    check_password(request)
    clipboard = pyperclip.paste()
    return {"status": "sent", "clipboard": clipboard}


@app.post("/clipboard")
async def set_clipboard(request: Request):
    check_password(request)
    form = await request.form()
    clipboard = form.get("clipboard", "")
    pyperclip.copy(clipboard)

    if clipboard.strip():
        add_to_history(clipboard)

    return {"status": "received", "clipboard": clipboard}


# === Main ===
if __name__ == "__main__":
    load_history_from_file()  # Load history when app starts

    root = tk.Tk()
    root.withdraw()  # Hide root window

    # Start FastAPI server
    threading.Thread(target=start_server, daemon=True).start()

    # Register Zeroconf
    ip_address = register_service()

    # Monitor IP changes
    monitor_thread = threading.Thread(target=monitor_ip_change, daemon=True)
    monitor_thread.start()

    # Create tray icon
    tray_icon = Icon("ClipboardSync", create_icon(), menu=create_tray_menu(ip_address))
    tray_thread = threading.Thread(target=tray_icon.run, daemon=True)
    tray_thread.start()
    tray_icon_active = True

    try:
        root.mainloop()  # Run GUI event loop in main thread
    except KeyboardInterrupt:
        print("🔴 Application terminated.")
