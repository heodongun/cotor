#!/usr/bin/env python3
import json
import re
import subprocess
import threading
from pathlib import Path
from http.server import BaseHTTPRequestHandler, HTTPServer

HOST = "127.0.0.1"
PORT = 18777
BASE = Path(__file__).resolve().parent
CONFIG_PATH = BASE / "services.json"

DEFAULT_TEMPLATES = [
    {"id": "openclaw-gateway", "name": "OpenClaw Gateway", "kind": "openclaw", "match": "openclaw-gateway", "startCmd": ""},
    {"id": "symphony-process", "name": "Symphony (Process)", "kind": "process", "match": "bin/symphony|/symphony ", "startCmd": ""},
    {"id": "symphony-docker", "name": "Symphony (Docker)", "kind": "docker_name", "match": "symphony", "startCmd": ""},
    {"id": "jagalchi-runserver", "name": "Jagalchi Runserver", "kind": "process", "match": "manage.py runserver", "startCmd": ""},
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
            by_id = {x.get("id"): x for x in data if isinstance(x, dict)}
            changed = False
            for d in DEFAULT_TEMPLATES:
                if d.get("id") not in by_id:
                    data.append(d)
                    changed = True
            if changed:
                save_templates(data)
            return data
    except Exception:
        pass
    return DEFAULT_TEMPLATES.copy()


def save_templates(items):
    CONFIG_PATH.write_text(json.dumps(items, ensure_ascii=False, indent=2), encoding="utf-8")


def process_metrics(pattern):
    rc, out = run(f"ps -axo pid=,rss=,pcpu=,args= | egrep '{pattern}' | egrep -v 'egrep' || true")
    rss_kb = 0.0
    cpu = 0.0
    pids = []
    for ln in out.splitlines():
        m = re.match(r"\s*(\d+)\s+(\d+)\s+([0-9.]+)\s+.*", ln)
        if not m:
            continue
        pids.append(int(m.group(1)))
        rss_kb += float(m.group(2))
        cpu += float(m.group(3))
    return {
        "pids": pids,
        "count": len(pids),
        "cpuPercent": round(cpu, 2),
        "rssMB": round(rss_kb / 1024.0, 1),
    }


def docker_metrics(name):
    rc, out = run("docker stats --no-stream --format '{{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}' || true")
    cpu = 0.0
    mem = 0.0
    count = 0
    for ln in out.splitlines():
        parts = ln.split("\t")
        if len(parts) < 3:
            continue
        cname, c_cpu, c_mem = parts[0], parts[1], parts[2]
        if name.lower() not in cname.lower():
            continue
        count += 1
        try:
            cpu += float(c_cpu.strip().replace("%", ""))
        except Exception:
            pass
        cur = c_mem.split("/")[0].strip()
        m = re.match(r"([0-9.]+)\s*([A-Za-z]+)", cur)
        if m:
            v = float(m.group(1))
            u = m.group(2).lower()
            if u.startswith("g"):
                mem += v * 1024
            elif u.startswith("m"):
                mem += v
            elif u.startswith("k"):
                mem += v / 1024
    return {"count": count, "cpuPercent": round(cpu, 2), "rssMB": round(mem, 1)}


def openclaw_action(action):
    if action in {"start", "stop", "restart", "status"}:
        rc, out = run(f"openclaw gateway {action}")
        return rc == 0, out
    if action == "kill":
        rc, out = run("pkill -KILL -f 'openclaw-gateway' || true")
        return True, out or "sent KILL to openclaw-gateway"
    return False, "invalid action"


def docker_name_action(name, action):
    if action == "status":
        rc, out = run(f"docker ps -a --filter name={name} --format 'table {{.Names}}\t{{.Image}}\t{{.Status}}'")
        return rc == 0, out
    if action == "start":
        rc, ids = run(f"docker ps -aq --filter name={name}")
        if rc != 0:
            return False, ids
        if not ids.strip():
            return True, f"No containers for: {name}"
        rc2, out = run(f"echo '{ids}' | xargs docker start")
        return rc2 == 0, out
    if action in {"stop", "kill"}:
        rc, ids = run(f"docker ps -aq --filter name={name}")
        if rc != 0:
            return False, ids
        if not ids.strip():
            return True, f"No containers for: {name}"
        rc2, out = run(f"echo '{ids}' | xargs docker {action}")
        return rc2 == 0, out
    return False, "invalid action"


def process_action(pattern, action, start_cmd=""):
    if action == "status":
        rc, out = run(f"pgrep -af '{pattern}' || true")
        return True, out
    if action == "start":
        if not start_cmd:
            return False, "startCmd not set for this process template"
        rc, out = run(f"nohup {start_cmd} >/tmp/local-stack-control.log 2>&1 & echo started")
        return rc == 0, out
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
        return process_action(match, action, tpl.get("startCmd", ""))
    return False, f"unknown kind: {kind}"


def template_metrics(tpl):
    kind, match = tpl.get("kind"), tpl.get("match", "")
    if kind == "openclaw":
        return process_metrics("openclaw-gateway")
    if kind == "process":
        return process_metrics(match)
    if kind == "docker_name":
        return docker_metrics(match)
    return {"count": 0, "cpuPercent": 0.0, "rssMB": 0.0}


def auto_detect():
    result = []
    for tpl in load_templates():
        ok, out = apply_template(tpl, "status")
        m = template_metrics(tpl)
        running = m.get("count", 0) > 0
        if tpl.get("kind") == "openclaw" and ("running" in out.lower() or "active" in out.lower()):
            running = True
        result.append({"template": tpl, "running": running, "status": out, "metrics": m, "ok": ok})
    return result


def parse_memory_mb(text):
    m = re.search(r"([0-9.]+)\s*([GMK])B", text, re.IGNORECASE)
    if not m:
        return 0.0
    val = float(m.group(1))
    unit = m.group(2).upper()
    if unit == "G":
        return val * 1024.0
    if unit == "K":
        return val / 1024.0
    return val


def system_metrics():
    rc, top_out = run("top -l 1 -n 0 | head -n 20")
    cpu_user = cpu_sys = cpu_idle = 0.0
    mem_used_mb = mem_free_mb = 0.0

    for ln in top_out.splitlines():
        if "CPU usage:" in ln:
            m = re.search(r"([0-9.]+)% user,\s*([0-9.]+)% sys,\s*([0-9.]+)% idle", ln)
            if m:
                cpu_user, cpu_sys, cpu_idle = map(float, m.groups())
        if ln.startswith("PhysMem:"):
            m = re.search(r"PhysMem:\s*([^,]+),\s*([^\.]+)", ln)
            if m:
                mem_used_mb = parse_memory_mb(m.group(1))
                mem_free_mb = parse_memory_mb(m.group(2))

    total_mb = round(mem_used_mb + mem_free_mb, 1)

    rc, ps_out = run("ps -axo pid=,%cpu=,%mem=,rss=,comm= | sort -k2 -nr | head -n 8")
    top_cpu = []
    for ln in ps_out.splitlines():
        p = ln.split(None, 4)
        if len(p) < 5:
            continue
        top_cpu.append({
            "pid": int(p[0]),
            "cpu": float(p[1]),
            "mem": float(p[2]),
            "rssMB": round(float(p[3]) / 1024.0, 1),
            "cmd": p[4],
        })

    rc, ps_mem_out = run("ps -axo pid=,%cpu=,%mem=,rss=,comm= | sort -k3 -nr | head -n 8")
    top_mem = []
    for ln in ps_mem_out.splitlines():
        p = ln.split(None, 4)
        if len(p) < 5:
            continue
        top_mem.append({
            "pid": int(p[0]),
            "cpu": float(p[1]),
            "mem": float(p[2]),
            "rssMB": round(float(p[3]) / 1024.0, 1),
            "cmd": p[4],
        })

    return {
        "cpu": {
            "user": round(cpu_user, 2),
            "sys": round(cpu_sys, 2),
            "idle": round(cpu_idle, 2),
            "used": round(cpu_user + cpu_sys, 2),
        },
        "memory": {
            "usedMB": round(mem_used_mb, 1),
            "freeMB": round(mem_free_mb, 1),
            "totalMB": total_mb,
            "usedPct": round((mem_used_mb / total_mb) * 100.0, 2) if total_mb else 0.0,
        },
        "topCpu": top_cpu,
        "topMem": top_mem,
    }


HTML = """<!doctype html><html lang='ko'><head><meta charset='utf-8'/><meta name='viewport' content='width=device-width,initial-scale=1'/><title>Local Stack Control</title>
<style>body{font-family:-apple-system,BlinkMacSystemFont,sans-serif;margin:20px;background:#0b0f14;color:#e6edf3}.wrap{max-width:980px;margin:0 auto}.card{background:#111826;border:1px solid #243244;border-radius:12px;padding:14px;margin:10px 0}button{background:#1f6feb;color:#fff;border:none;border-radius:8px;padding:7px 10px;cursor:pointer;margin-right:6px;margin-bottom:6px}.danger{background:#d73a49}.muted{background:#3a4a5f}input,select{background:#0d1117;color:#e6edf3;border:1px solid #30363d;border-radius:8px;padding:8px;margin-right:6px}pre{background:#0d1117;border:1px solid #30363d;border-radius:8px;padding:10px;white-space:pre-wrap}.metric{font-size:13px;color:#9fb1c7}</style></head>
<body><div class='wrap'><h2>🧰 Local Stack Control</h2>
<div class='card'><button onclick='refresh()'>새로고침</button><button onclick='startAll()'>전체 켜기 (start all)</button><button class='danger' onclick='cleanup()'>전체 정리 (cleanup)</button></div>
<div id='system'></div>
<div id='services'></div>
<div class='card'><h3>서비스 템플릿 추가</h3>
<input id='name' placeholder='표시 이름'/><select id='kind'><option value='process'>process</option><option value='docker_name'>docker_name</option><option value='openclaw'>openclaw</option></select>
<input id='match' placeholder='match/pattern'/><input id='startCmd' placeholder='start command (옵션)' style='width:320px'/><button onclick='addTpl()'>추가</button>
</div>
<div class='card'><h3>Output</h3><pre id='out'>ready</pre></div></div>
<script>
const out=document.getElementById('out');
async function j(url,opt){const r=await fetch(url,opt);return await r.json();}
function esc(s){return (s||'').replaceAll('<','&lt;')}
function shortCmd(s){const v=String(s||''); return v.length>80? (v.slice(0,80)+'…') : v;}
function renderTopRows(title, rows){
  return `<div style='margin-top:8px'><b>${title}</b><br/>` + rows.map(r => `• PID ${r.pid} | CPU ${r.cpu}% | MEM ${r.mem}% | RSS ${r.rssMB}MB | ${esc(shortCmd(r.cmd))}`).join('<br/>') + '</div>';
}
async function refresh(){
  const [d,sys]=await Promise.all([j('/api/detect'), j('/api/system')]);
  const s=document.getElementById('system');
  const c=sys.cpu||{}, m=sys.memory||{};
  s.innerHTML=`<div class='card'><h3>시스템 리소스</h3><div class='metric'>CPU 사용: ${c.used||0}% (user ${c.user||0}% / sys ${c.sys||0}% / idle ${c.idle||0}%)</div><div class='metric'>메모리 사용: ${m.usedMB||0} MB / ${m.totalMB||0} MB (${m.usedPct||0}%) | 남음: ${m.freeMB||0} MB</div>${renderTopRows('Top CPU', sys.topCpu||[])}${renderTopRows('Top Memory', sys.topMem||[])}</div>`;

  const box=document.getElementById('services');box.innerHTML='';
  d.items.forEach(item=>{const t=item.template,m=item.metrics||{};const c=document.createElement('div');c.className='card';c.innerHTML=`<h3>${esc(t.name)} ${item.running?'🟢':'⚪️'}</h3><div class='metric'>CPU: ${m.cpuPercent||0}% | RSS: ${m.rssMB||0} MB | Proc/Container: ${m.count||0}</div><div style='margin-top:8px'><button onclick="act('${t.id}','start')">Start</button><button onclick="act('${t.id}','status')">Status</button><button class='muted' onclick="act('${t.id}','stop')">Stop</button><button class='muted' onclick="delTpl('${t.id}')">삭제</button></div><small>${esc(t.kind)} / ${esc(t.match||'')}</small>`;box.appendChild(c);});}
async function act(id,action){const d=await j('/api/action',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({id,action})});out.textContent=(d.ok?'':'ERROR\n')+d.output;setTimeout(refresh,500)}
async function startAll(){const d=await j('/api/start_all',{method:'POST'});out.textContent=(d.ok?'':'ERROR\n')+d.output;setTimeout(refresh,700)}
async function cleanup(){const d=await j('/api/cleanup',{method:'POST'});out.textContent=(d.ok?'':'ERROR\n')+d.output;setTimeout(refresh,700)}
async function addTpl(){const name=document.getElementById('name').value.trim();const kind=document.getElementById('kind').value;const match=document.getElementById('match').value.trim();const startCmd=document.getElementById('startCmd').value.trim();const d=await j('/api/templates',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({name,kind,match,startCmd})});out.textContent=(d.ok?'':'ERROR\n')+d.output;refresh()}
async function delTpl(id){const d=await j('/api/templates/delete',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({id})});out.textContent=(d.ok?'':'ERROR\n')+d.output;refresh()}
refresh(); setInterval(refresh, 5000);
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
        if self.path == "/api/system":
            self._json({"ok": True, **system_metrics()})
            return
        self.send_error(404)

    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        raw = self.rfile.read(length).decode("utf-8") if length else "{}"
        data = json.loads(raw or "{}")

        if self.path == "/api/action":
            tpl = next((x for x in load_templates() if x.get("id") == data.get("id")), None)
            if not tpl:
                self._json({"ok": False, "output": "template not found"}, 404)
                return
            ok, output = apply_template(tpl, data.get("action"))
            self._json({"ok": ok, "output": output}, 200 if ok else 500)
            return

        if self.path == "/api/templates":
            name, kind = data.get("name", "").strip(), data.get("kind")
            if not name or not kind:
                self._json({"ok": False, "output": "name/kind required"}, 400)
                return
            items = load_templates()
            tid = re.sub(r"[^a-z0-9-]", "-", name.lower()).strip("-") or "service"
            tid = f"{tid}-{len(items)+1}"
            items.append({"id": tid, "name": name, "kind": kind, "match": data.get("match", "").strip(), "startCmd": data.get("startCmd", "").strip()})
            save_templates(items)
            self._json({"ok": True, "output": f"added: {name}"})
            return

        if self.path == "/api/templates/delete":
            tid = data.get("id")
            save_templates([x for x in load_templates() if x.get("id") != tid])
            self._json({"ok": True, "output": f"deleted: {tid}"})
            return

        if self.path == "/api/start_all":
            outputs, ok_all = [], True
            for item in auto_detect():
                tpl = item["template"]
                ok, out = apply_template(tpl, "start")
                outputs.append(f"[{tpl.get('name')}] start\n{out}")
                ok_all = ok_all and ok
            self._json({"ok": ok_all, "output": "\n\n".join(outputs)})
            return

        if self.path == "/api/cleanup":
            outputs, ok_all = [], True
            for item in auto_detect():
                tpl = item["template"]
                action = "stop" if tpl.get("kind") == "openclaw" else "kill"
                ok, out = apply_template(tpl, action)
                outputs.append(f"[{tpl.get('name')}] {action}\n{out}")
                ok_all = ok_all and ok
            self._json({"ok": ok_all, "output": "\n\n".join(outputs)})
            return

        self.send_error(404)


def run_server(host=HOST, port=PORT):
    httpd = HTTPServer((host, port), Handler)
    print(f"Local control app: http://{host}:{port}")
    httpd.serve_forever()


def run_server_background(host=HOST, port=PORT):
    t = threading.Thread(target=run_server, args=(host, port), daemon=True)
    t.start()
    return t


if __name__ == "__main__":
    run_server()
