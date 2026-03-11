#!/usr/bin/env python3
"""Byte-accurate PTY bridge for the macOS desktop shell.

This script spawns the target command inside a pseudo-terminal and forwards
stdin/stdout between the parent process and the PTY master without the extra
formatting/noise that `/usr/bin/script` introduces.
"""

from __future__ import annotations

import os
import pty
import selectors
import signal
import subprocess
import sys


def main() -> int:
    args = sys.argv[1:]
    if args[:1] == ["--"]:
        args = args[1:]

    if not args:
        print("pty_bridge.py requires a target command", file=sys.stderr)
        return 64

    master_fd, slave_fd = pty.openpty()
    proc = subprocess.Popen(
        args,
        stdin=slave_fd,
        stdout=slave_fd,
        stderr=slave_fd,
        close_fds=True,
        start_new_session=True,
    )
    os.close(slave_fd)

    def terminate_child(signum: int, _frame) -> None:
        try:
            os.killpg(proc.pid, signum)
        except ProcessLookupError:
            pass

    signal.signal(signal.SIGTERM, terminate_child)
    signal.signal(signal.SIGINT, terminate_child)

    selector = selectors.DefaultSelector()
    selector.register(master_fd, selectors.EVENT_READ, "pty")
    selector.register(sys.stdin.fileno(), selectors.EVENT_READ, "stdin")

    stdin_open = True
    pty_open = True

    while stdin_open or pty_open:
        for key, _ in selector.select(timeout=0.1):
            if key.data == "stdin":
                chunk = os.read(sys.stdin.fileno(), 4096)
                if not chunk:
                    selector.unregister(sys.stdin.fileno())
                    stdin_open = False
                    continue
                os.write(master_fd, chunk)
            else:
                try:
                    chunk = os.read(master_fd, 4096)
                except OSError:
                    chunk = b""
                if not chunk:
                    selector.unregister(master_fd)
                    pty_open = False
                    continue
                os.write(sys.stdout.fileno(), chunk)

        if proc.poll() is not None and not pty_open:
            break

    try:
        os.close(master_fd)
    except OSError:
        pass

    return proc.wait()


if __name__ == "__main__":
    raise SystemExit(main())
