#!/usr/bin/env python3
import socket, threading, subprocess, os

LISTEN_HOST='127.0.0.1'
LISTEN_PORT=4000
CMD=['docker','exec','-i','symphony','nc','127.0.0.1','4000']


def sock_to_proc(sock, proc):
    try:
        while True:
            data = sock.recv(65536)
            if not data:
                break
            os.write(proc.stdin.fileno(), data)
    except Exception:
        pass
    finally:
        try: proc.stdin.close()
        except Exception: pass


def proc_to_sock(proc, sock):
    try:
        while True:
            data = os.read(proc.stdout.fileno(), 65536)
            if not data:
                break
            sock.sendall(data)
    except Exception:
        pass
    finally:
        try: sock.shutdown(socket.SHUT_WR)
        except Exception: pass
        try: sock.close()
        except Exception: pass


def handle(conn):
    proc = subprocess.Popen(CMD, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, bufsize=0)
    t1 = threading.Thread(target=sock_to_proc, args=(conn,proc), daemon=True)
    t2 = threading.Thread(target=proc_to_sock, args=(proc,conn), daemon=True)
    t1.start(); t2.start()


srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
srv.bind((LISTEN_HOST, LISTEN_PORT))
srv.listen(256)
while True:
    c,_ = srv.accept()
    threading.Thread(target=handle, args=(c,), daemon=True).start()
