"""Katalogdaki paket kimliklerini internetten doğrular.

F-Droid: f-droid.org/api/v1/packages/<paket> 200 dönmeli.
Play Store: play.google.com/store/apps/details?id=<paket> 200 dönmeli (404 = yok).
Sonuç GitHub Actions özetine yazılır. Sorun bulunsa da derlemeyi durdurmaz
(Play bazen istekleri sınırlar); --strict verilirse hata koduyla çıkar.
"""
import json
import os
import sys
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CATALOG = os.path.join(ROOT, "app", "src", "main", "assets", "catalog.json")
UA = "Mozilla/5.0 (Atolye katalog kontrolu)"


def status(url):
    for attempt in range(3):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept-Language": "tr"})
            with urllib.request.urlopen(req, timeout=20) as r:
                return r.status
        except urllib.error.HTTPError as e:
            if e.code in (429, 500, 502, 503) and attempt < 2:
                time.sleep(2 * (attempt + 1))
                continue
            return e.code
        except Exception:
            if attempt < 2:
                time.sleep(2)
                continue
            return 0
    return 0


def check(p):
    if p["source"] == "fdroid":
        code = status("https://f-droid.org/api/v1/packages/" + p["pkg"])
        alt = status("https://play.google.com/store/apps/details?id=" + p["pkg"]) if code != 200 else None
    else:
        code = status("https://play.google.com/store/apps/details?id=" + p["pkg"] + "&hl=tr")
        alt = None
    return p, code, alt


def main():
    with open(CATALOG, encoding="utf-8") as f:
        progs = json.load(f)["programs"]
    with ThreadPoolExecutor(8) as ex:
        results = list(ex.map(check, progs))
    bad = [(p, c, a) for p, c, a in results if c != 200]
    lines = [f"### Paket kontrolü: {len(progs) - len(bad)}/{len(progs)} doğrulandı", ""]
    if bad:
        lines += ["| Uygulama | Kaynak | Paket | HTTP | Play'de |", "|---|---|---|---|---|"]
        for p, c, a in bad:
            lines.append(f"| {p['name']} | {p['source']} | `{p['pkg']}` | {c} | {a or ''} |")
    text = "\n".join(lines)
    print(text)
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as f:
            f.write(text + "\n")
    if bad and "--strict" in sys.argv:
        sys.exit(1)


if __name__ == "__main__":
    main()
