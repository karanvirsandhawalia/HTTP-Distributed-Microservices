"""Simple round-robin load balancer for OrderServer instances.

Listens on a single IP/PORT and distributes incoming requests
across multiple OrderServer backends using round-robin.
"""

from http.server import BaseHTTPRequestHandler, HTTPServer
from socketserver import ThreadingMixIn
import urllib.request
import urllib.error
import json
import threading

with open("config.json") as f:
    config = json.load(f)

# List of OrderServer backends to distribute across
BACKENDS = config["LoadBalancer"]["backends"]

LB_IP = config["LoadBalancer"]["ip"]
LB_PORT = config["LoadBalancer"]["port"]

# Round-robin counter
current = 0
lock = threading.Lock()

def get_next_backend():
    """Return the next backend in round-robin order."""
    global current
    with lock:
        backend = BACKENDS[current % len(BACKENDS)]
        current += 1
    return backend


class LoadBalancerHandler(BaseHTTPRequestHandler):

    def log_message(self, format, *args):
        """Silence access logs for performance."""
        pass

    def forward_request(self, method):
        """Forward the incoming request to the next backend."""
        backend = get_next_backend()
        url = f"http://{backend['ip']}:{backend['port']}{self.path}"

        content_length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(content_length) if content_length > 0 else None

        req = urllib.request.Request(url, data=body, method=method)

        # Copy headers
        for key, value in self.headers.items():
            if key.lower() not in ("host", "content-length"):
                req.add_header(key, value)
        if body:
            req.add_header("Content-Length", str(len(body)))

        try:
            with urllib.request.urlopen(req, timeout=10) as response:
                resp_body = response.read()
                self.send_response(response.status)
                for key, value in response.headers.items():
                    if key.lower() not in ("transfer-encoding",):
                        self.send_header(key, value)
                self.end_headers()
                self.wfile.write(resp_body)
        except urllib.error.HTTPError as e:
            resp_body = e.read()
            self.send_response(e.code)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(resp_body)
        except Exception as e:
            self.send_response(502)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(b'{"error": "Bad Gateway"}')

    def do_GET(self):
        self.forward_request("GET")

    def do_POST(self):
        self.forward_request("POST")


class ThreadedHTTPServer(ThreadingMixIn, HTTPServer):
    """Handle each request in a separate thread."""
    daemon_threads = True


def run():
    server = ThreadedHTTPServer((LB_IP, LB_PORT), LoadBalancerHandler)
    print(f"Load balancer running on http://{LB_IP}:{LB_PORT}")
    print(f"Backends: {BACKENDS}")
    server.serve_forever()


run()
