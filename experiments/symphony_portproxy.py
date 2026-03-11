#!/usr/bin/env python3
import subprocess
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

class Proxy(BaseHTTPRequestHandler):
    def _forward(self, method):
        length = int(self.headers.get('Content-Length', '0'))
        body = self.rfile.read(length) if length else b''
        cmd = [
            'docker','exec','-i','symphony','sh','-lc',
            f"curl -sS -i -X {method} 'http://127.0.0.1:4000{self.path}'"
        ]
        if body:
            cmd[-1] = f"curl -sS -i -X {method} --data-binary @- 'http://127.0.0.1:4000{self.path}'"
        p = subprocess.run(cmd, input=body if body else None, capture_output=True)
        raw = p.stdout or p.stderr
        head, _, rest = raw.partition(b'\r\n\r\n')
        if not rest:
            head, _, rest = raw.partition(b'\n\n')
        status = 502
        headers = []
        for i, line in enumerate(head.splitlines()):
            s = line.decode('utf-8', 'ignore').strip()
            if i == 0 and s.startswith('HTTP/'):
                try: status = int(s.split()[1])
                except: status = 502
            elif ':' in s:
                k,v = s.split(':',1)
                lk = k.lower().strip()
                if lk not in ('transfer-encoding','connection','content-encoding'):
                    headers.append((k.strip(), v.strip()))
        self.send_response(status)
        for k,v in headers:
            self.send_header(k,v)
        if not any(k.lower()=='content-length' for k,_ in headers):
            self.send_header('Content-Length', str(len(rest)))
        self.end_headers()
        self.wfile.write(rest)

    def do_GET(self): self._forward('GET')
    def do_POST(self): self._forward('POST')
    def do_PUT(self): self._forward('PUT')
    def do_DELETE(self): self._forward('DELETE')

if __name__ == '__main__':
    server = ThreadingHTTPServer(('127.0.0.1', 4000), Proxy)
    server.serve_forever()
