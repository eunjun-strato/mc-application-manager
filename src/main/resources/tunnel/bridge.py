"""Private Jupyter network-namespace bridge; no host TCP port is published."""
import select
import socket
import socketserver
import threading

LIMIT = threading.BoundedSemaphore(32)


class Handler(socketserver.BaseRequestHandler):
    def handle(self):
        if not LIMIT.acquire(blocking=False):
            return
        upstream = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        upstream.settimeout(45)
        self.request.settimeout(45)
        try:
            upstream.connect('/run/mcmp-tunnel/gateway.sock')
            sockets = [self.request, upstream]
            while True:
                ready, _, _ = select.select(sockets, [], [], 45)
                if not ready:
                    return
                for source in ready:
                    chunk = source.recv(65536)
                    if not chunk:
                        return
                    (upstream if source is self.request else self.request).sendall(chunk)
        except OSError:
            pass  # Traffic contains bearer tokens: never log it.
        finally:
            upstream.close()
            LIMIT.release()


class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True

    def process_request(self, request, client_address):
        # Bound worker creation as well as established upstream connections.
        if threading.active_count() > 40:
            request.close()
        else:
            super().process_request(request, client_address)


if __name__ == '__main__':
    with Server(('127.0.0.1', 18084), Handler) as server:
        server.serve_forever()
