"""RideScan server. Run: python desktop/server.py  then open http://localhost:8000

- Driver phone (RideScan app) POSTs each notification to /event. A START/END event flips the ride state.
- Cab tablet (CabScreen app) polls /state: is a ride on, and which videos to play.
- Website (index.html) uploads/deletes videos, shows notifications, and can start/end a ride by hand.
Admin actions (upload, delete, clear, manual ride) only work from this laptop."""
import json
import re
import sys
import threading
import time
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
from pathlib import Path
from urllib.parse import parse_qs, unquote, urlparse

HERE = Path(__file__).parent
LOG = HERE / "events.jsonl"
RIDE = HERE / "ride.json"
VIDEOS = HERE / "videos"
PORT = 8000
VIDEO_EXT = {".mp4", ".webm", ".mkv", ".3gp", ".mov"}
lock = threading.Lock()  # requests run on separate threads; keep file writes from interleaving
tablets = {}  # ip -> {"seen": ms, "playing": str} reported by each tablet on every poll

VIDEOS.mkdir(exist_ok=True)


def now_ms():
    return int(time.time() * 1000)


def read_lines():
    return LOG.read_text(encoding="utf-8").splitlines() if LOG.exists() else []


# ponytail: one global ride state = one cab (one driver phone + one tablet).
# For a fleet, key this by a cab id that both apps send.
def get_ride():
    try:
        return json.loads(RIDE.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {"active": False, "since": 0, "source": ""}


def set_ride(active, source):
    with lock:
        RIDE.write_text(json.dumps({"active": active, "since": now_ms(), "source": source}), encoding="utf-8")
    print("RIDE", "STARTED" if active else "ENDED", "by", source)


# The server decides START/END itself, so phrases can change without reinstalling the phone app.
# Keep in step with RideParser.java on the phone (the phone's label is used when it already says START/END).
END_WORDS = ["ride completed", "trip completed", "ride ended", "trip ended", "ride is over", "ride over",
             "trip is over", "ride has ended", "trip has ended", "ride has been ended", "ride is ended",
             "ride has been completed", "trip has been completed", "collect cash", "collect payment",
             "drop completed", "you have reached the drop"]
START_WORDS = ["ride started", "trip started", "ride has started", "ride is started", "ride has been started",
               "trip has started", "started the ride", "started the trip", "ride is on", "otp verified",
               "heading to drop", "on the way to drop"]
WHATSAPP_TEST_SENDER = "chituuuuuu"  # test mode: only this WhatsApp contact can start/stop a ride


def classify(event):
    if event.get("app") == "WhatsApp" and WHATSAPP_TEST_SENDER not in str(event.get("title", "")).lower():
        return "OTHER"  # nobody else on WhatsApp can trigger the ads
    if event.get("type") in ("START", "END"):
        return event["type"]
    t = f'{event.get("title", "")} {event.get("text", "")}'.lower()
    if any(w in t for w in END_WORDS):
        return "END"
    if any(w in t for w in START_WORDS):
        return "START"
    return "OTHER"


def video_list():
    files = sorted((p for p in VIDEOS.iterdir() if p.suffix.lower() in VIDEO_EXT), key=lambda p: p.stat().st_mtime)
    return [{"name": p.name, "size": p.stat().st_size} for p in files]


def safe_name(name):
    """Plain file name only: no folders, no odd characters, must be a video extension."""
    name = re.sub(r"[^\w.\- ]", "_", Path(unquote(name)).name).strip()
    return name if name and Path(name).suffix.lower() in VIDEO_EXT else None


class Handler(BaseHTTPRequestHandler):
    def local(self):
        if self.client_address[0] == "127.0.0.1":
            return True
        self.send_error(403, "Only allowed from the laptop running the server")
        return False

    def query(self, key, default=""):
        return parse_qs(urlparse(self.path).query).get(key, [default])[0]

    def do_POST(self):
        path = urlparse(self.path).path
        if path == "/event":
            return self.on_event()
        if path == "/upload":
            return self.on_upload()
        if not self.local():
            return
        if path == "/clear":
            with lock:
                LOG.unlink(missing_ok=True)
            print("History cleared")
        elif path == "/ride":
            set_ride(self.query("state") == "start", "website")
        elif path == "/delete":
            name = safe_name(self.query("name"))
            if not name:
                return self.send_error(400)
            (VIDEOS / name).unlink(missing_ok=True)
            print("Deleted video", name)
        else:
            return self.send_error(404)
        self.reply(b"{}", "application/json")

    def on_event(self):
        try:
            n = int(self.headers.get("Content-Length", 0))
            event = json.loads(self.rfile.read(n))
            if not isinstance(event, dict):
                raise ValueError
        except ValueError:
            return self.send_error(400)
        event["ip"] = self.client_address[0]
        event["type"] = classify(event)
        with lock, LOG.open("a", encoding="utf-8") as f:
            f.write(json.dumps(event, ensure_ascii=False) + "\n")
        print(event.get("device") or event["ip"], "|", event.get("app"), event.get("type"),
              event.get("title"), "-", event.get("text"))
        if event.get("type") in ("START", "END"):
            set_ride(event["type"] == "START", f'{event.get("app")} on {event.get("device") or event["ip"]}')
        self.send_response(204)
        self.end_headers()

    def on_upload(self):
        if not self.local():
            return
        name = safe_name(self.headers.get("X-Filename", ""))
        if not name:
            return self.send_error(400, "Not a video file (use mp4, webm, mkv, 3gp or mov)")
        left = int(self.headers.get("Content-Length", 0))
        part = VIDEOS / (name + ".part")
        with part.open("wb") as f:  # stream to disk: videos can be hundreds of MB
            while left > 0:
                chunk = self.rfile.read(min(left, 1 << 20))
                if not chunk:
                    break
                f.write(chunk)
                left -= len(chunk)
        if left:
            part.unlink(missing_ok=True)
            return self.send_error(400, "Upload interrupted")
        part.replace(VIDEOS / name)
        print("Uploaded video", name)
        self.reply(b"{}", "application/json")

    def do_GET(self):
        path = urlparse(self.path).path
        if path == "/events":
            raw = self.query("since", "0")
            since = int(raw) if raw.isdigit() else 0
            lines = read_lines()
            body = '{"total":%d,"events":[%s]}' % (len(lines), ",".join(lines[since:]))
            self.reply(body.encode(), "application/json")
        elif path == "/state":
            # Polled by tablets every second; they report what they are playing via ?playing=
            if self.query("tablet"):
                ip = self.client_address[0]
                t = {"seen": now_ms(), "playing": self.query("playing"), "name": self.query("tablet"),
                     "loc": tablets.get(ip, {}).get("loc")}  # keep the last known location
                try:
                    t["loc"] = {"lat": float(self.query("lat")), "lng": float(self.query("lng")),
                                "acc": int(self.query("acc") or 0) or None, "fix": int(self.query("fix") or 0) or None}
                except ValueError:
                    pass  # no fix in this poll
                tablets[ip] = t
            body = {"ride": get_ride(), "videos": video_list(), "tablets": tablets}
            self.reply(json.dumps(body).encode(), "application/json")
        elif path.startswith("/videos/"):
            name = safe_name(path[len("/videos/"):])
            if not name or not (VIDEOS / name).exists():
                return self.send_error(404)
            self.send_file(VIDEOS / name)
        elif path == "/":
            self.reply((HERE / "index.html").read_bytes(), "text/html; charset=utf-8")
        else:
            self.send_error(404)

    def send_file(self, p):
        self.send_response(200)
        self.send_header("Content-Type", "application/octet-stream")
        self.send_header("Content-Length", str(p.stat().st_size))
        self.end_headers()
        with p.open("rb") as f:
            while chunk := f.read(1 << 20):
                self.wfile.write(chunk)

    def reply(self, body, ctype):
        self.send_response(200)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *a):  # keep the console for events only
        pass


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")  # Windows console can't print emoji otherwise
    print(f"RideScan server on port {PORT}. Open http://localhost:{PORT}")
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
