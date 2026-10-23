#!/usr/bin/env python3
"""Downloads sample z14 vector tiles (OpenFreeMap) plus the matching Overpass street data for a few
areas, so the tile-based street network can be compared with the Overpass one in unit tests."""
import json, math, os, sys, time, urllib.parse, urllib.request

UA = "FoggyMap-CI/1.0 (+https://github.com/JustFunLP/FoggyMap; test fixtures)"
OUT = sys.argv[1] if len(sys.argv) > 1 else "tiles-sample"
MODE = sys.argv[2] if len(sys.argv) > 2 else "all"   # tiles | overpass | all
AREAS = {
    "helsinki": (60.1716, 24.9443),
    "solingen": (51.1755, 7.0845),
    "berlin": (52.5385, 13.4240),
    "odenthal": (51.0330, 7.1150),
}
Z = 14


def get(url, data=None, tries=4):
    for k in range(tries):
        try:
            req = urllib.request.Request(url, data=data, headers={"User-Agent": UA, "Accept-Encoding": "identity"})
            with urllib.request.urlopen(req, timeout=100) as r:
                return r.read()
        except Exception as e:  # noqa
            print(f"  retry {url[:80]} ({e})")
            time.sleep(5 * (k + 1))
    raise SystemExit(f"failed: {url}")


def tile_of(lat, lon):
    n = 2 ** Z
    x = int((lon + 180) / 360 * n)
    y = int((1 - math.asinh(math.tan(math.radians(lat))) / math.pi) / 2 * n)
    return x, y


def bounds(x, y):
    n = 2 ** Z
    def lat(yy):
        return math.degrees(math.atan(math.sinh(math.pi * (1 - 2 * yy / n))))
    return lat(y + 1), x / n * 360 - 180, lat(y), (x + 1) / n * 360 - 180   # s w n e


def expand(b, m):
    s, w, n, e = b
    dlat = m / 111320.0
    dlon = m / (111320.0 * math.cos(math.radians((s + n) / 2)))
    return s - dlat, w - dlon, n + dlat, e + dlon


ROADS = """[out:json][timeout:90][maxsize:134217728];
(
  way["highway"]["highway"!~"^(proposed|construction|abandoned|platform|raceway|elevator|bus_stop|rest_area|services|razed)$"]({b});
  way["railway"~"^(rail|light_rail|subway|narrow_gauge)$"]({b});
  way["waterway"~"^(river|canal)$"]({b});
);
out body geom qt;"""
BUILDINGS = """[out:json][timeout:90];
way["building"]({b});
out tags geom qt;"""

os.makedirs(OUT, exist_ok=True)
if MODE == "overpass":
    meta = json.load(open(f"{OUT}/meta.json"))
    for name, a in meta["areas"].items():
        q = a["queryBox"]
        bb = f"{q[0]:.6f},{q[1]:.6f},{q[2]:.6f},{q[3]:.6f}"
        for kind, tpl in (("roads", ROADS), ("buildings", BUILDINGS)):
            body = urllib.parse.urlencode({"data": tpl.replace("{b}", bb)}).encode()
            for ep in ("https://overpass-api.de/api/interpreter", "https://overpass.private.coffee/api/interpreter", "https://maps.mail.ru/osm/tools/overpass/api/interpreter"):
                try:
                    t0 = time.time()
                    js = get(ep, body, tries=1)
                    print(name, kind, ep.split("/")[2], f"{time.time()-t0:.0f}s", len(js))
                    if b'"elements"' in js:
                        open(f"{OUT}/{name}/overpass_{kind}.json", "wb").write(js)
                        break
                except SystemExit as e:
                    print(e)
    sys.exit(0)
tilejson = json.loads(get("https://tiles.openfreemap.org/planet"))
json.dump(tilejson, open(f"{OUT}/tilejson.json", "w"), indent=1)
template = tilejson["tiles"][0]
print("template", template)
meta = {"template": template, "areas": {}}
for name, (lat, lon) in AREAS.items():
    cx, cy = tile_of(lat, lon)
    d = f"{OUT}/{name}"
    os.makedirs(d, exist_ok=True)
    sizes = []
    for dx in (-1, 0, 1):
        for dy in (-1, 0, 1):
            x, y = cx + dx, cy + dy
            raw = get(template.replace("{z}", str(Z)).replace("{x}", str(x)).replace("{y}", str(y)))
            if raw[:2] == b"\x1f\x8b":
                import gzip
                raw = gzip.decompress(raw)
            open(f"{d}/{Z}_{x}_{y}.pbf", "wb").write(raw)
            sizes.append(len(raw))
    q = expand(bounds(cx, cy), 400)
    meta["areas"][name] = {"center": [lat, lon], "tile": [cx, cy], "queryBox": q, "tileBytes": sizes}
    print(name, cx, cy, "tile bytes", sum(sizes))
json.dump(meta, open(f"{OUT}/meta.json", "w"), indent=1)
