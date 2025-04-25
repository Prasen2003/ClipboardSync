from fastapi import FastAPI, Form, Request
from fastapi.middleware.cors import CORSMiddleware
import pyperclip
import threading
import uvicorn
import socket
import tkinter as tk

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

# === Start FastAPI server in a thread ===
def start_server():
    uvicorn.run(app, host="0.0.0.0", port=8000)

# === Get IP address ===
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

# === Simple GUI using tkinter ===
def launch_ui():
    ip = get_ip()
    window = tk.Tk()
    window.title("📋 Clipboard Sync Server")

    tk.Label(window, text="Server is running!", font=("Helvetica", 16)).pack(pady=10)
    tk.Label(window, text=f"Your IP Address: {ip}", font=("Helvetica", 14), fg="blue").pack(pady=10)
    tk.Label(window, text="Use this IP in your Android app", font=("Helvetica", 12)).pack(pady=5)
    tk.Label(window, text="Listening on port: 8000", font=("Helvetica", 12)).pack(pady=5)

    window.geometry("320x200")
    window.mainloop()

# === Main ===
if __name__ == "__main__":
    threading.Thread(target=start_server, daemon=True).start()
    launch_ui()
