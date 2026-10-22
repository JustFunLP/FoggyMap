#!/usr/bin/env bash
# End-to-end test on an Android emulator (run by GitHub Actions):
# install the debug APK, start exploring, drive a 1.1 km route through real Helsinki streets
# via simulated GPS, then check the database and collect screenshots + logs.
set -u
PKG=de.foggymap.app.debug
OUT=e2e-out
mkdir -p "$OUT"
shot() {
  adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS > /dev/null 2>&1 || true
  sleep 1
  adb exec-out screencap -p > "$OUT/$1.png"; echo "screenshot $1"
}

# Local group-sync server (the real Node.js server from server/) reachable from the emulator as 10.0.2.2.
rm -f /tmp/foggy-e2e.db*
PORT=8787 DB_FILE=/tmp/foggy-e2e.db node server/src/node-server.js > "$OUT/server.log" 2>&1 &
SERVER_PID=$!
sleep 1

adb wait-for-device
adb shell settings put secure location_mode 3 || true
# A software-rendered CI emulator is slow; never show "isn't responding" dialogs over the app.
adb shell settings put global hide_error_dialogs 1 || true
adb shell settings put global anr_show_background 0 || true
adb install -r -g out/FoggyMap-debug.apk
adb shell pm grant $PKG android.permission.ACCESS_FINE_LOCATION || true
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS || true
adb logcat -G 16M || true
adb logcat -c

# Pre-seed the two route cells with a real OSM extract (Helsinki, ODbL) so the test does not depend on
# the overloaded public Overpass servers. Neighbour cells are still downloaded live by the app.
gzip -c app/src/test/resources/helsinki_overpass.json > /tmp/helsinki.json.gz
adb push /tmp/helsinki.json.gz /data/local/tmp/helsinki.json.gz > /dev/null
adb shell chmod 644 /data/local/tmp/helsinki.json.gz
for c in c_7521_2078 c_7521_2079; do
  adb shell "run-as $PKG sh -c 'mkdir -p files/osm && cp /data/local/tmp/helsinki.json.gz files/osm/$c.json.gz'"
done
adb shell "run-as $PKG ls -la files/osm" || true

read -r LON LAT < scripts/e2e_route.txt
for i in 1 2 3; do adb emu geo fix "$LON" "$LAT"; sleep 1; done

adb shell am start -n $PKG/de.foggymap.app.MainActivity --ez e2e_autostart true --es e2e_server http://10.0.2.2:8787
# Wait until the street data of the start cell is loaded (Overpass can take a while).
for i in $(seq 1 90); do
  adb emu geo fix "$LON" "$LAT" > /dev/null
  if adb logcat -d -s FoggyMap:I | grep -q "cell loaded"; then echo "map data loaded after ${i}x2 s"; break; fi
  sleep 2
done
sleep 5
shot 01-start

# Drive the route like a real trip: the app goes to the background right away, and for the second half
# the screen is off (phone in the pocket). Only the foreground service runs. A software-rendered CI
# emulator without GPU freezes completely while the 3D map is on screen, so the map is only opened again
# at the end for the screenshots.
shot 02-unterwegs
adb shell input keyevent KEYCODE_HOME
sleep 3
TOTAL=$(wc -l < scripts/e2e_route.txt)
n=0
while read -r LON LAT <&3; do
  adb emu geo fix "$LON" "$LAT" > /dev/null < /dev/null
  n=$((n+1))
  if [ $n -eq $((TOTAL/2)) ]; then adb shell input keyevent KEYCODE_SLEEP < /dev/null; echo "screen off after $n points"; fi
  sleep 1
done 3< scripts/e2e_route.txt
echo "drove $n route points"
# Stay at the destination (screen still off) until all queued positions could be processed.
for i in $(seq 1 45); do adb emu geo fix "$LON" "$LAT" > /dev/null; sleep 2; done
adb exec-out run-as $PKG cat databases/foggymap.db > "$OUT/foggymap.db" 2>/dev/null || true
adb exec-out run-as $PKG cat databases/foggymap.db-wal > "$OUT/foggymap.db-wal" 2>/dev/null || true

adb shell input keyevent KEYCODE_WAKEUP
sleep 2
adb shell wm dismiss-keyguard || adb shell input keyevent 82
adb shell am start -n $PKG/de.foggymap.app.MainActivity > /dev/null
sleep 25
shot 03-ziel
# 2D overview (layers button toggles the perspective)
SIZE=$(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | tail -1); W=${SIZE%x*}; H=${SIZE#*x}
adb shell input tap $((W*90/100)) $((H*435/1000)); sleep 8; shot 03b-2d
adb shell input tap $((W*90/100)) $((H*435/1000)); sleep 3

# Statistics sheet: tap the bottom card (screen-size independent: 80 % height, centre).
SIZE=$(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | tail -1); W=${SIZE%x*}; H=${SIZE#*x}
adb shell input tap $((W/2)) $((H*76/100)); sleep 3; shot 04-statistik
adb shell input keyevent KEYCODE_BACK; sleep 2
adb shell input tap $((W*73/100)) $((H*85/1000)); sleep 3; shot 05-koop
adb shell input keyevent KEYCODE_BACK; sleep 1

# Give the app a moment to upload the last reveals (sync is debounced by ~20 s).
sleep 30
kill $SERVER_PID 2>/dev/null || true
cp /tmp/foggy-e2e.db* "$OUT/" 2>/dev/null || true
adb logcat -d > "$OUT/logcat-full.txt"
grep -E "FoggyMap|FoggyMatcher|AndroidRuntime|FATAL|maplibre|MapLibre|foggymap" "$OUT/logcat-full.txt" > "$OUT/logcat-app.txt" || true

python3 - "$OUT" <<'PY'
import sqlite3, sys, os, shutil
out = sys.argv[1]
db = os.path.join(out, "foggymap.db")
summary = []
log = open(os.path.join(out, "logcat-full.txt"), errors="ignore").read().splitlines()
# Only crashes of FoggyMap count (the emulator image's own apps sometimes crash).
crash = any("FATAL EXCEPTION" in l and i + 1 < len(log) and "de.foggymap" in log[i + 1] for i, l in enumerate(log))
native = [log[i + 1] for i, l in enumerate(log) if ">>> de.foggymap" in l and i + 1 < len(log)]
gl_driver = any("libGLESv2_enc.so" in l for l in log if " F DEBUG " in l)
if native:
    summary.append("Nativer Absturz: " + ("im OpenGL-Übersetzer des Emulators (libGLESv2_enc.so)" if gl_driver else "JA"))
live = [l for l in log if "FoggyMap" in l and ("cache=false" in l or "failed:" in l)]
summary.append("Live-Download Overpass: " + ("; ".join(l.split("FoggyMap: ")[-1] for l in live[:3]) if live else "nicht nötig/keiner"))
summary.append(f"App-Absturz im Log: {'JA' if crash else 'nein'}")
counts = {}
if os.path.getsize(db) > 0:
    con = sqlite3.connect(db)
    for kind, n in con.execute("select kind, count(*) from reveal group by kind"):
        counts[kind] = n
    km = con.execute("select coalesce(sum(len),0)/1000.0 from reveal where kind='r'").fetchone()[0]
    summary.append(f"Aufgedeckt: {counts.get('r',0)} Straßenabschnitte ({km:.2f} km), {counts.get('b',0)} Blöcke, "
                   f"{counts.get('s',0)} Teilflächen, {counts.get('g',0)} Gebäude")
else:
    summary.append("Datenbank konnte nicht gelesen werden")
server_items = 0
sdb = os.path.join(out, "foggy-e2e.db")
if os.path.exists(sdb):
    try:
        scon = sqlite3.connect(sdb)
        server_items = scon.execute("select count(*) from items").fetchone()[0]
        groups = scon.execute("select count(*) from groups").fetchone()[0]
        members = [r[0] for r in scon.execute("select name from members")]
        summary.append(f"Koop-Server: {groups} Gruppe(n), Mitglieder {members}, {server_items} synchronisierte Elemente "
                       f"(App: {sum(counts.values())})")
    except Exception as e:
        summary.append(f"Koop-Server-DB nicht lesbar: {e}")
open(os.path.join(out, "summary.txt"), "w").write("\n".join(summary) + "\n")
print("\n".join(summary))
ok = (not crash) and counts.get('r', 0) >= 12
sys.exit(0 if ok else 1)
PY
