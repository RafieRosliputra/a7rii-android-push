"""JS Live Photo moderator: checks guest photos before they appear on the big screen.

Runs on m10 next to the livephoto app (CPU only, photos never leave the server).
  POST /check {"path": "EVENTS/<event>/public upload/guest-....webp"}
       -> {"verdict": "ok|flag|block", "labels": [...], "scores": {...}, "ms": 1234}
  GET  /health
Models (downloaded once into $HF_HOME):
  NSFW  Falconsai/nsfw_image_detection   (image classifier: normal / nsfw)
  CLIP  openai/clip-vit-base-patch32     (zero-shot against the prompts in rules.py)
  OCR   RapidOCR (ONNX)                  (how much of the photo is text)
"""
import json
import os
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from PIL import Image, ImageOps

import rules

PHOTOS = Path(os.environ.get("PHOTOS_DIR", "/photos")).resolve()
PORT = int(os.environ.get("PORT", "8096"))
lock = threading.Lock()   # one photo at a time: steady CPU use, predictable memory
models = {}


def load():
    import torch
    from transformers import pipeline
    from rapidocr_onnxruntime import RapidOCR
    torch.set_num_threads(int(os.environ.get("THREADS", "4")))
    t = time.time()
    models["nsfw"] = pipeline("image-classification", model="Falconsai/nsfw_image_detection", device=-1)
    models["clip"] = pipeline("zero-shot-image-classification", model="openai/clip-vit-base-patch32", device=-1)
    models["ocr"] = RapidOCR()
    print(f"models ready in {time.time() - t:.1f}s", flush=True)


def text_share(img):
    """Share of the image area covered by OCR text boxes (0..1)."""
    small = img.copy()
    small.thumbnail((1024, 1024))
    result, _ = models["ocr"](small)
    if not result:
        return 0.0
    area = 0.0
    for box, _text, conf in result:
        if float(conf) < 0.5:
            continue
        xs = [p[0] for p in box]
        ys = [p[1] for p in box]
        area += (max(xs) - min(xs)) * (max(ys) - min(ys))
    return min(1.0, area / float(small.width * small.height))


def check(rel):
    path = (PHOTOS / rel).resolve()
    if PHOTOS not in path.parents or not path.is_file():
        raise FileNotFoundError(rel)
    t = time.time()
    with Image.open(path) as im:
        img = ImageOps.exif_transpose(im).convert("RGB")
    img.thumbnail((768, 768))
    with lock:
        nsfw_out = models["nsfw"](img)
        nsfw = next((r["score"] for r in nsfw_out if r["label"].lower() == "nsfw"), 0.0)
        clip_out = models["clip"](img, candidate_labels=rules.PROMPTS)
        clip = {r["label"]: float(r["score"]) for r in clip_out}
        text = text_share(img)
    verdict, labels = rules.decide(nsfw, clip, text)
    top = sorted(clip.items(), key=lambda kv: -kv[1])[:3]
    return {
        "verdict": verdict, "labels": labels, "ms": int((time.time() - t) * 1000),
        "scores": {"nsfw": round(nsfw, 3), "text": round(text, 3), "top": [[k, round(v, 3)] for k, v in top]},
    }


class Handler(BaseHTTPRequestHandler):
    def reply(self, code, obj):
        data = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("content-type", "application/json")
        self.send_header("content-length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.path == "/health":
            return self.reply(200 if models else 503, {"ready": bool(models)})
        self.reply(404, {"error": "not found"})

    def do_POST(self):
        if self.path != "/check":
            return self.reply(404, {"error": "not found"})
        if not models:
            return self.reply(503, {"error": "models loading"})
        try:
            body = json.loads(self.rfile.read(int(self.headers.get("content-length") or 0)) or b"{}")
            out = check(str(body.get("path", "")))
            print(f"{out['verdict']:5} {out['ms']:5}ms {out['labels']} {body.get('path')}", flush=True)
            self.reply(200, out)
        except FileNotFoundError:
            self.reply(404, {"error": "photo not found"})
        except Exception as e:  # noqa: BLE001 - report and let the server retry / flag
            print("error:", repr(e), flush=True)
            self.reply(500, {"error": str(e)})

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    threading.Thread(target=load, daemon=True).start()
    print(f"moderator on :{PORT}, photos {PHOTOS}", flush=True)
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
