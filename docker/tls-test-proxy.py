"""CI-only TLS terminator: never copied into the Manager image."""
import http.client
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import ssl

class Proxy(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass
    def do_GET(self): self.forward()
    def do_POST(self): self.forward()
    def forward(self):
        body = self.rfile.read(int(self.headers.get('Content-Length', 0)))
        ignored = {'host', 'connection', 'content-length', 'forwarded', 'via', 'x-real-ip', 'cf-connecting-ip'}
        headers = {k: v for k, v in self.headers.items() if k.lower() not in ignored and not k.lower().startswith('x-forwarded-')}
        headers['Host'] = 'wesort.test'
        headers['X-Forwarded-For'] = self.client_address[0]
        headers['X-Forwarded-Proto'] = 'https'
        connection = http.client.HTTPConnection('manager', 8765, timeout=15)
        try:
            connection.request(self.command, self.path, body, headers)
            response = connection.getresponse()
            content = response.read()
            self.send_response(response.status)
            for key, value in response.getheaders():
                if key.lower() not in {'connection', 'transfer-encoding', 'content-length'}:
                    self.send_header(key, value)
            self.send_header('Content-Length', str(len(content)))
            self.end_headers()
            self.wfile.write(content)
        finally:
            connection.close()

server = ThreadingHTTPServer(('0.0.0.0', 4443), Proxy)
context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
context.load_cert_chain('/cert/server.crt', '/cert/server.key')
server.socket = context.wrap_socket(server.socket, server_side=True)
server.serve_forever()
