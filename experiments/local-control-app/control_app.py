#!/usr/bin/env python3
import json
import os
import re
import subprocess
from http.server import BaseHTTPRequestHandler, HTTPServer

HOST = "127.0.0.1"
PORT = 18777

HTML = """<!doctype html>
<html lang="ko">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>Local Stack Control</title>
  <style>
    body { font-family: -apple-system, BlinkMacSystemFont, sans-serif; margin: 24px; background:#0b0f14; color:#e6edf3; }
    .wrap { max-width: 900px; margin: 0 auto; }
    .card { background:#111826; border:1px solid #243244; border-radius:12px; padding:16px; margin-bottom:12px; }
    .row { display:flex; gap:8px; flex-wrap:wrap; align-items:center; }
    button { background:#1f6feb; color:white; border:none; border-radius:8px; padding:8px 12px; cursor:pointer; }
    button.danger { background:#d73a49; }
    button.secondary { background:#3a4a5f; }
    .status { margin-left:auto; color:#8b949e; font-size:13px; }
    pre { background:#0d1117; border:1px solid #30363d; border-radius:8px; padding:12px; white-space:pre-wrap; }
  </style>
</head>
<body>
  <div class="wrap">
    <h2>🧰 Local Stack Control</h2>
    <p>OpenClaw / Symphony / Jagalchi 빠른 제어</p>

    <div class="card">
      <h3>OpenClaw Gateway</h3>
      <div class="row">
        <button onclick="act('openclaw','start')">Start</button>
        <button class="secondary" onclick="act('openclaw','status')">Status</button>
        <button class="secondary" onclick="act('openclaw','stop')">Stop</button>
        <button onclick="act('openclaw','restart')">Restart</button>
      </div>
    </div>

    <div class="card">
      <h3>Symphony (Docker)</h3>
      <div class="row">
        <button class="secondary" onclick="act('symphony','status')">Status</button>
        <button class="secondary" onclick="act('symphony','stop')">Stop</button>
        <button class="danger" onclick="act('symphony','kill')">Kill</button>
      </div>
      <p style="color:#8b949e;font-size:13px">컨테이너 이름에 <code>symphony</code> 포함된 것 대상</p>
    </div>

    <div class="card">
      <h3>Jagalchi Dev Server</h3>
      <div class="row">
        <button class="secondary" onclick="act('jagalchi','status')">Status</button>
        <button class="secondary" onclick="act('jagalchi','stop')">Stop</button>
        <button class="danger" onclick="act('jagalchi','kill')">Kill</button>
      </div>
      <p style="color:#8b949e;font-size:13px"><code>manage.py runserver</code> 패턴 대상</p>
    </div>

    <div class="card">
      <h3>Output</h3>
      <pre id="out">ready</pre>
    </div>
  </div>

<script>
async function act(service, action) {
  const out = document.getElementById('out');
  out.textContent = `running: ${service} ${action} ...`;
  const res = await fetch('/api/action', {
    method: 'POST',
    headers: {'Content-Type':'application/json'},
    body: JSON.stringify({service, action})
  });
  const data = await res.json();
  out.textContent = data.ok ? data.output : ('ERROR\n' + data.output);
}
</script>
</body>
</html>
"""


def run(cmd):
    p = subprocess.run(cmd, shell=True, capture_output=True, text=True)
    out = (p.stdout or "") + (p.stderr or "")
    return p.returncode, out.strip() or "(no output)"


def openclaw(action):
    if action not in {"start", "stop", "restart", "status"}:
        return False, "invalid action"
    returncode, out = run(f"openclaw gateway {action}")
    return returncode == 0, out


def symphony(action):
    if action == "status":
        returncode, out = run("docker ps -a --filter name=symphony --format 'table {{.Names}}\\t{{.Image}}\\t{{.Status}}'")
        return returncode == 0, out
    if action in {"stop", "kill"}:
        rc, ids = run("docker ps -aq --filter name=symphony")
        if rc != 0:
            return False, ids
        ids = ids.strip()
        if not ids:
            return True, "No symphony containers"
        returncode, out = run(f"echo '{ids}' | xargs docker {action}")
        return returncode == 0, out
    return False, "invalid action"


def jagalchi(action):
    pattern = r"manage.py runserver"
    if action == "status":
        rc, out = run("pgrep -af 'manage.py runserver|jagalchi-server-AI' || true")
        lines = [ln for ln in out.splitlines() if re.search(pattern, ln)]
        if not lines:
            return True, "Jagalchi runserver not running"
        return True, "\n".join(lines)
    if action in {"stop", "kill"}:
        sig = "-TERM" if action == "stop" else "-KILL"
        rc, out = run(f"pkill {sig} -f 'manage.py runserver|jagalchi-server-AI.*manage.py runserver' || true")
        return True, out or f"sent {sig}"
    return False, "invalid action"


ACTIONS = {
    "openclaw": openclaw,
    "symphony": symphony,
    "jagalchi": jagalchi,
}


class Handler(BaseHTTPRequestHandler):
    def _json(self, obj, code=200):
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path in ["/", "/index.html"]:
            body = HTML.encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        self.send_error(404)

    def do_POST(self):
        if self.path != "/api/action":
            self.send_error(404)
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
            raw = self.rfile.read(length).decode("utf-8")
            data = json.loads(raw or "{}")
        except Exception as e:
            self._json({"ok": False, "output": f"bad request: {e}"}, 400)
            return

        service = data.get("service")
        action = data.get("action")
        fn = ACTIONS.get(service)
        if not fn:
            self._json({"ok": False, "output": "unknown service"}, 400)
            return

        ok, output = fn(action)
        self._json({"ok": ok, "output": output}, 200 if ok else 500)


if __name__ == "__main__":
    print(f"Local control app: http://{HOST}:{PORT}")
    HTTPServer((HOST, PORT), Handler).serve_forever()
