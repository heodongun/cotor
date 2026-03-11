#!/usr/bin/env python3
import subprocess
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

class H(BaseHTTPRequestHandler):
    def _go(self, method):
        length = int(self.headers.get('Content-Length','0') or '0')
        body = self.rfile.read(length) if length else b''
        cmd = "curl -sS -i -X {} '{}'".format(method, 'http://127.0.0.1:4000'+self.path)
        if body:
            cmd = "curl -sS -i -X {} --data-binary @- '{}'".format(method, 'http://127.0.0.1:4000'+self.path)
        p = subprocess.run(['docker','exec','-i','symphony','sh','-lc',cmd], input=body if body else None, capture_output=True)
        raw = p.stdout
        if not raw:
            self.send_response(502); self.end_headers(); self.wfile.write((p.stderr or b'proxy upstream error')); return
        sep = b'\r\n\r\n' if b'\r\n\r\n' in raw else b'\n\n'
        head, _, rest = raw.partition(sep)
        lines = head.splitlines()
        status=200
        if lines and lines[0].startswith(b'HTTP/'):
            try: status=int(lines[0].split()[1])
            except: status=502
        self.send_response(status)
        for ln in lines[1:]:
            if b':' in ln:
                k,v=ln.split(b':',1)
                kl=k.decode('utf-8','ignore').strip().lower()
                if kl in ('transfer-encoding','connection','content-encoding'): continue
                self.send_header(k.decode('utf-8','ignore').strip(), v.decode('utf-8','ignore').strip())
        self.send_header('Content-Length', str(len(rest)))
        self.end_headers(); self.wfile.write(rest)
    def do_GET(self): self._go('GET')
    def do_POST(self): self._go('POST')
    def do_PUT(self): self._go('PUT')
    def do_DELETE(self): self._go('DELETE')

srv = ThreadingHTTPServer(('127.0.0.1',4000), H)
srv.serve_forever()
