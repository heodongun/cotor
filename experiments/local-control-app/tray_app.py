#!/usr/bin/env python3
"""
Menubar app wrapper for Local Stack Control.
Requires: rumps
"""
import subprocess
import time
import webbrowser
from pathlib import Path

import rumps

BASE = Path(__file__).resolve().parent
SERVER = BASE / "control_app.py"
URL = "http://127.0.0.1:18777"


def sh(cmd):
    return subprocess.run(cmd, shell=True, capture_output=True, text=True)


class LocalControlTray(rumps.App):
    def __init__(self):
        super().__init__("🧰", quit_button="Quit")
        self.server = None
        self.menu = [
            "Open Dashboard",
            "Start Server",
            "Stop Server",
            None,
            "Quick: OpenClaw Stop",
            "Quick: Symphony Kill",
            "Quick: Jagalchi Kill",
        ]

    def start_server(self):
        if self.server and self.server.poll() is None:
            return
        self.server = subprocess.Popen(["python3", str(SERVER)], cwd=str(BASE))
        time.sleep(0.6)

    @rumps.clicked("Open Dashboard")
    def open_dashboard(self, _):
        self.start_server()
        webbrowser.open(URL)

    @rumps.clicked("Start Server")
    def start_srv(self, _):
        self.start_server()
        rumps.notification("Local Stack Control", "Server", "Started")

    @rumps.clicked("Stop Server")
    def stop_srv(self, _):
        if self.server and self.server.poll() is None:
            self.server.terminate()
            rumps.notification("Local Stack Control", "Server", "Stopped")
        else:
            rumps.notification("Local Stack Control", "Server", "Not running")

    @rumps.clicked("Quick: OpenClaw Stop")
    def quick_oc(self, _):
        sh("openclaw gateway stop")
        rumps.notification("Quick Action", "OpenClaw", "stop sent")

    @rumps.clicked("Quick: Symphony Kill")
    def quick_sym(self, _):
        sh("docker ps -aq --filter name=symphony | xargs -I{} docker kill {}")
        rumps.notification("Quick Action", "Symphony", "kill sent")

    @rumps.clicked("Quick: Jagalchi Kill")
    def quick_jg(self, _):
        sh("pkill -KILL -f 'manage.py runserver|jagalchi-server-AI.*manage.py runserver' || true")
        rumps.notification("Quick Action", "Jagalchi", "kill sent")

    def quit(self, _=None):
        if self.server and self.server.poll() is None:
            self.server.terminate()
        super().quit()


if __name__ == "__main__":
    app = LocalControlTray()
    app.start_server()
    app.run()
