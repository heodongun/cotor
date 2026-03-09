#!/usr/bin/env python3
import json
import re
import subprocess
from pathlib import Path
from http.server import BaseHTTPRequestHandler, HTTPServer

HOST = "0.0.0.0"
PORT = 18777
BASE = Path(__file__).resolve().parent
CONFIG_PATH = BASE / "services.json"

DEFAULT_TEMPLATES = [
    {
        "id": "openclaw-gateway",
        "name": "OpenClaw Gateway",
        "kind": "openclaw",
        "match": "openclaw-gateway",
    },
    {
        "id": "symphony-docker",
        "name": "Symphony (Docker)",
        "kind": "docker_name",
        "match": "symphony",
    },
    {
        "id": "jagalchi-runserver",
        "name": "Jagalchi Runserver",
        "kind": "process",
        "match": "manage.py runserver",
    },
]


def run(cmd):
    p = subprocess.run(cmd, shell=True, capture_output=True, text=True)
    out = (p.stdout or "") + (p.stderr or "")
    return p.returncode, (out.strip() or "(no output)")


def load_templates():
    if not CONFIG_PATH.exists():
        save_templates(DEFAULT_TEMPLATES)
        return DEFAULT_TEMPLATES.copy()
    try:
        data = json.loads(CONFIG_PATH.read_text(encoding="utf-8"))
        if isinstance(data, list):
            return data
    except Exception:
        pass
    return DEFAULT_TEMPLATES.copy()


def save_templates(items):
    CONFIG_PATH.write_text(json.dumps(items, ensure_ascii=False, indent=2), encoding="utf-8")


def openclaw_action(action):
    if action not in {"start", "stop", "restart", "status"}:
        return False, "invalid action"
    rc, out = run(f"openclaw gateway {action}")
    return rc == 0, out


def docker_name_action(name, action):
    if action == "status":
        rc, out = run(f"docker ps -a --filter name={name} --format 'table {{.Names}}\\t{{.Image}}\\t{{.Status}}'")
        return rc == 0, out
    if action in {"stop", "kill"}:
        rc, ids = run(f"docker ps -aq --filter name={name}")
        if rc != 0:
            return False, ids
        if not ids.strip():
            return True, f"No containers for: {name}"
        rc2, out = run(f"echo '{ids}' | xargs docker {action}")
        return rc2 == 0, out
    return False, "invalid action"


def process_action(pattern, action):
    if action == "status":
        rc, out = run(f"pgrep -af '{pattern}' || true")
        return True, out
    if action in {"stop", "kill"}:
        sig = "-TERM" if action == "stop" else "-KILL"
        rc, out = run(f"pkill {sig} -f '{pattern}' || true")
        return True, out or f"sent {sig}"
    return False, "invalid action"


def apply_template(tpl, action):
    kind, match = tpl.get("kind"), tpl.get("match", "")
    if kind == "openclaw":
        return openclaw_action(action)
    if kind == "docker_name":
        return docker_name_action(match, action)
    if kind == "process":
        return process_action(match, action)
    return False, f"unknown kind: {kind}"


def auto_detect():
    templates = load_templates()
    result = []
    for tpl in templates:
        ok, out = apply_template(tpl, "status")
        running = False
        if tpl.get("kind") == "openclaw":
            running = "running" in out.lower() or "active" in out.lower()
        elif tpl.get("kind") == "docker_name":
            running = bool(re.search(r"Up ", out))
        elif tpl.get("kind") == "process":
            running = out != "(no output)"
        result.append({"template": tpl, "running": running, "status": out, "ok": ok})
    return result


HTML = """<!doctype html><html lang='ko'><head><meta charset='utf-8'/><meta name='viewport' content='width=device-width,initial-scale=1'/><title>Local Stack Control</title>
<style>body{font-family:-apple-system,BlinkMacSystemFont,sans-serif;margin:20px;background:#0b0f14;color:#e6edf3}.wrap{max-width:980px;margin:0 auto}.card{background:#111826;border:1px solid #243244;border-radius:12px;padding:14px;margin:10px 0}button{background:#1f6feb;color:#fff;border:none;border-radius:8px;padding:7px 10px;cursor:pointer;margin-right:6px;margin-bottom:6px}.danger{background:#d73a49}.muted{background:#3a4a5f}input,select{background:#0d1117;color:#e6edf3;border:1px solid #30363d;border-radius:8px;padding:8px;margin-right:6px}pre{background:#0d1117;border:1px solid #30363d;border-radius:8px;padding:10px;white-space:pre-wrap}</style></head>
<body><div class='wrap'><h2>🧰 Local Stack Control</h2>
<div class='card'><button onclick='refresh()'>자동탐지 새로고침</button><button class='danger' onclick='cleanup()'>전체 정리 (cleanup)</button></div>
<div id='services'></div>
<div class='card'><h3>서비스 템플릿 추가</h3>
<input id='name' placeholder='표시 이름'/><select id='kind'><option value='process'>process (pgrep/pkill)</option><option value='docker_name'>docker_name</option><option value='openclaw'>openclaw</option></select><input id='match' placeholder='match/pattern (예: manage.py runserver)'/><button onclick='addTpl()'>추가</button>
</div>
<div class='card'><h3>Output</h3><pre id='out'>ready</pre></div></div>
<script>
const out = document.getElementById('out');
async function j(url,opt){const r=await fetch(url,opt);return await r.json();}
function esc(s){return (s||'').replaceAll('<','&lt;');}
async function refresh(){const d=await j('/api/detect');const box=document.getElementById('services');box.innerHTML='';d.items.forEach(item=>{const t=item.template;const card=document.createElement('div');card.className='card';card.innerHTML=`<h3>${esc(t.name)} ${item.running?'🟢':'⚪️'}</h3><div><button onclick="act('${t.id}','status')">Status</button><button class='muted' onclick="act('${t.id}','stop')">Stop</button><button class='danger' onclick="act('${t.id}','kill')">Kill</button><button class='muted' onclick="delTpl('${t.id}')">삭제</button></div><small>${esc(t.kind)} / ${esc(t.match||'')}</small>`;box.appendChild(card);});}
async function act(id,action){const d=await j('/api/action',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({id,action})});out.textContent=(d.ok?'':'ERROR\n')+d.output;await refresh();}
async function cleanup(){const d=await j('/api/cleanup',{method:'POST'});out.textContent=(d.ok?'':'ERROR\n')+d.output;await refresh();}
async function addTpl(){const name=document.getElementById('name').value.trim();const kind=document.getElementById('kind').value;const match=document.getElementById('match').value.trim();const d=await j('/api/templates',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({name,kind,match})});out.textContent=(d.ok?'':'ERROR\n')+d.output;await refresh();}
async function delTpl(id){const d=await j('/api/templates/delete',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({id})});out.textContent=(d.ok?'':'ERROR\n')+d.output;await refresh();}
refresh();
</script></body></html>"""


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
            b = HTML.encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(b)))
            self.end_headers()
            self.wfile.write(b)
            return
        if self.path == "/api/detect":
            self._json({"ok": True, "items": auto_detect()})
            return
        self.send_error(404)

    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        raw = self.rfile.read(length).decode("utf-8") if length else "{}"
        data = json.loads(raw or "{}")

        if self.path == "/api/action":
            templates = load_templates()
            tid, action = data.get("id"), data.get("action")
            tpl = next((x for x in templates if x.get("id") == tid), None)
            if not tpl:
                self._json({"ok": False, "output": "template not found"}, 404)
                return
            ok, output = apply_template(tpl, action)
            self._json({"ok": ok, "output": output}, 200 if ok else 500)
            return

        if self.path == "/api/templates":
            name, kind, match = data.get("name", "").strip(), data.get("kind"), data.get("match", "").strip()
            if not name or not kind:
                self._json({"ok": False, "output": "name/kind required"}, 400)
                return
            items = load_templates()
            tid = re.sub(r"[^a-z0-9-]", "-", name.lower()).strip("-") or "service"
            tid = f"{tid}-{len(items)+1}"
            items.append({"id": tid, "name": name, "kind": kind, "match": match})
            save_templates(items)
            self._json({"ok": True, "output": f"added: {name}"})
            return

        if self.path == "/api/templates/delete":
            tid = data.get("id")
            items = [x for x in load_templates() if x.get("id") != tid]
            save_templates(items)
            self._json({"ok": True, "output": f"deleted: {tid}"})
            return

        if self.path == "/api/cleanup":
            outputs = []
            ok_all = True
            for item in auto_detect():
                tpl = item["template"]
                # OpenClaw는 stop, 나머지는 kill 우선
                action = "stop" if tpl.get("kind") == "openclaw" else "kill"
                ok, out = apply_template(tpl, action)
                outputs.append(f"[{tpl.get('name')}] {action}\n{out}")
                ok_all = ok_all and ok
            self._json({"ok": ok_all, "output": "\n\n".join(outputs)})
            return

        self.send_error(404)


if __name__ == "__main__":
    print(f"Local control app: http://{HOST}:{PORT}")
    HTTPServer((HOST, PORT), Handler).serve_forever()
