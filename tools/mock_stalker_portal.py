#!/usr/bin/env python3
"""
Portail Stalker/MAG de DÉMONSTRATION pour tester le module StalkerClient de Binaire IPTV.

But : vérifier le comportement du client (handshake, jeton, pagination, create_link,
erreurs) SANS toucher à un vrai fournisseur. Ne sert qu'avec des contenus de démo
libres de droits (échantillons Blender/Big Buck Bunny) ou des URL bidon.

Lancement :
    python3 tools/mock_stalker_portal.py 8088
Puis, dans Binaire, ajoute un abonnement « Portail MAC » :
    Portail : http://<ip-de-ta-machine>:8088/c
    MAC     : 00:1A:79:AA:BB:CC   (n'importe laquelle, sauf 00:00:00:00:00:00 = « bloqué »)

Options de test d'erreurs :
    MAC 00:00:00:00:00:00  -> simule un appareil bloqué
    En-tête sans token     -> le serveur renvoie une erreur « token expired » une fois
"""
import json
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

TOKEN = "DEMO_TOKEN_123"
PER_PAGE = 14
# Contenu de démo : échantillon vidéo libre de droits (Blender Foundation, CC-BY).
SAMPLE = "http://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4"

def genres(n=3):
    return [{"id": str(i), "title": f"Démo Genre {i}"} for i in range(1, n + 1)]

def items(prefix, total):
    return [{"id": f"{prefix}{i}", "name": f"{prefix.upper()} Démo {i}",
             "cmd": f"ffmpeg {SAMPLE}", "number": str(i)} for i in range(1, total + 1)]

class H(BaseHTTPRequestHandler):
    def log_message(self, *a):  # silencieux
        pass

    def _send(self, obj):
        body = json.dumps(obj).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        q = parse_qs(urlparse(self.path).query)
        mac = ""
        for c in (self.headers.get("Cookie") or "").split(";"):
            if "mac=" in c:
                mac = c.split("mac=", 1)[1].strip()
        action = q.get("action", [""])[0]
        typ = q.get("type", [""])[0]

        # Appareil « bloqué » de démonstration.
        if mac.replace("%3A", ":") == "00:00:00:00:00:00":
            return self._send({"js": {"block": 1}})

        if action == "handshake":
            return self._send({"js": {"token": TOKEN}})
        if action == "get_profile":
            return self._send({"js": {"id": "1", "name": "Démo", "status": 1}})
        if action == "get_genres" or (action == "get_categories"):
            return self._send({"js": genres()})
        if action == "get_ordered_list":
            page = int(q.get("p", ["1"])[0])
            total = 30 if typ == "itv" else 20
            data = items(typ or "x", total)
            start = (page - 1) * PER_PAGE
            chunk = data[start:start + PER_PAGE]
            return self._send({"js": {"total_items": total, "max_page_items": PER_PAGE, "data": chunk}})
        if action == "create_link":
            return self._send({"js": {"cmd": f"ffmpeg {SAMPLE}"}})
        return self._send({"js": {"error": "unknown action"}})

if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8088
    print(f"Portail Stalker de démo sur http://0.0.0.0:{port}/c  (Ctrl+C pour arrêter)")
    ThreadingHTTPServer(("0.0.0.0", port), H).serve_forever()
