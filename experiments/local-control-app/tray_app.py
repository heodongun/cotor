#!/usr/bin/env python3
"""Menubar app wrapper for Local Stack Control."""
import time
import webbrowser
import subprocess

import rumps
import control_app

URL = "http://127.0.0.1:18777"


def sh(cmd):
    return subprocess.run(cmd, shell=True, capture_output=True, text=True)


class LocalControlTray(rumps.App):
    def __init__(self):
        super().__init__("🧰", quit_button="Quit")
        self.server_thread = None
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
        if self.server_thread and self.server_thread.is_alive():
            return
        self.server_thread = control_app.run_server_background("127.0.0.1", 18777)
        time.sleep(0.5)

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
        # lightweight 서버라 트레이 종료 시 같이 끝나는 모델로 운영
        rumps.notification("Local Stack Control", "Server", "Stop은 앱 종료로 처리 (Quit)")

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


if __name__ == "__main__":
    app = LocalControlTray()
    app.start_server()
    app.run()
