#!/usr/bin/env python3
import socket, threading

LISTEN_HOST='127.0.0.1'
LISTEN_PORT=4000
TARGET_HOST='127.0.0.1'
TARGET_PORT=4100


def pipe(src, dst):
    try:
        while True:
            data = src.recv(65536)
            if not data:
                break
            dst.sendall(data)
    except Exception:
        pass
    finally:
        try: dst.shutdown(socket.SHUT_WR)
        except Exception: pass


def handle(client):
    upstream = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    upstream.connect((TARGET_HOST, TARGET_PORT))
    t1 = threading.Thread(target=pipe, args=(client, upstream), daemon=True)
    t2 = threading.Thread(target=pipe, args=(upstream, client), daemon=True)
    t1.start(); t2.start()


srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
srv.bind((LISTEN_HOST, LISTEN_PORT))
srv.listen(200)
while True:
    c, _ = srv.accept()
    threading.Thread(target=handle, args=(c,), daemon=True).start()
