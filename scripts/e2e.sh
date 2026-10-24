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
# Activity recognition (Play services, like Google Maps): GPS off while standing.
adb shell pm grant $PKG android.permission.ACTIVITY_RECOGNITION || true
adb logcat -G 16M || true
adb logcat -c

# No pre-seeded map data: the app downloads the vector tiles live from OpenFreeMap, exactly like on a phone.
read -r LON LAT < scripts/e2e_route.txt
for i in 1 2 3; do adb emu geo fix "$LON" "$LAT"; sleep 1; done

T_START=$(date +%s)
adb shell am start -n $PKG/de.foggymap.app.MainActivity --ez e2e_autostart true --es e2e_server http://10.0.2.2:8787
# Wait until the street data of the start cell is there (download + street graph).
LOADED=""
for i in $(seq 1 90); do
  adb emu geo fix "$LON" "$LAT" > /dev/null
  if adb logcat -d -s FoggyMap:I | grep -q "FoggyMap: cell t_"; then LOADED=$(( $(date +%s) - T_START )); echo "map data ready after ${LOADED} s"; break; fi
  sleep 1
done
echo "${LOADED:-nicht innerhalb von 90 s}" > "$OUT/first-cell-seconds.txt"
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
# Move the map away by hand, then the location button brings it back (position fixed, viewing direction up).
SIZE=$(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | tail -1); W=${SIZE%x*}; H=${SIZE#*x}
adb shell input swipe $((W/2)) $((H*40/100)) $((W/2)) $((H*20/100)) 400; sleep 6; shot 03b-verschoben
# Buttons are found by their accessibility label (layout independent).
tap_attr() {   # tap_attr <content-desc|text> <prefix>
  adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1
  adb pull /sdcard/ui.xml /tmp/ui.xml > /dev/null 2>&1
  local b
  b=$(grep -oE "$1=\"$2[^\"]*\"[^>]*bounds=\"\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]\"" /tmp/ui.xml | head -1 | grep -oE '[0-9]+' | tail -4 | tr '\n' ' ')
  if [ -z "$b" ]; then echo "UI element '$2' not found"; return 1; fi
  set -- $b
  adb shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
}
tap_desc() { tap_attr content-desc "$1"; }
tap_text() { tap_attr text "$1"; }
tap_desc "Auf meinen Standort" || tap_desc "Karte folgt dir" || true; sleep 8; shot 03c-standort
tap_desc "Karte nach Norden" || true; sleep 4; shot 03d-norden
tap_desc "Zur 2D" || true; sleep 4; shot 03e-2d
tap_desc "Zur 3D" || true; sleep 2

# Menu → Info (statistics and GPS state), then Koop.
tap_desc "Menü" || tap_text "Menü" || true; sleep 3; shot 04-info
adb shell input keyevent KEYCODE_BACK; sleep 2
tap_desc "Menü" || tap_text "Menü" || true; sleep 2; tap_text "Koop" || true; sleep 2; shot 05-koop
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
cells = [l.split("FoggyMap: ")[-1] for l in log if "FoggyMap: cell t_" in l]
summary.append("Kartenzellen (Vektorkacheln, live geladen): " + ("; ".join(cells[:4]) if cells else "KEINE"))
try:
    summary.append("Erste Zelle bereit nach: " + open(os.path.join(out, "first-cell-seconds.txt")).read().strip() + " s (ab App-Start)")
except Exception:
    pass
failed = [l.split("FoggyMap: ")[-1] for l in log if "FoggyMap: cell" in l and "failed:" in l]
if failed: summary.append("Fehlgeschlagene Ladeversuche: " + "; ".join(failed[:3]))
follow = [l.split("FoggyMap: ")[-1] for l in log if "ui: camera" in l]
if follow: summary.append("Kamera zuletzt: " + follow[-1])
summary.append(f"App-Absturz im Log: {'JA' if crash else 'nein'}")
import re
profiles = re.findall(r"gps profile -> (\w+)", "\n".join(log))
summary.append("GPS-Stufen: " + (" → ".join(profiles[-8:]) if profiles else "keine Wechsel"))
src = [l.split("FoggyMap: ")[-1] for l in log if "FoggyMap: location source:" in l]
if src: summary.append("Standortdienst: " + src[-1].replace("location source: ", ""))
motion = [l.split("FoggyMap: ")[-1] for l in log if "FoggyMap: motion" in l]
summary.append("Bewegungserkennung: " + ("; ".join(motion[-4:]) if motion else "keine Meldung"))
gpsmin = [l.split("FoggyMap: gps: ")[-1].split(" positions")[0] for l in log if "FoggyMap: gps: " in l]
if gpsmin: summary.append("GPS-Positionen pro Minute: " + ", ".join(gpsmin[-8:]))
fog_updates = sum(1 for l in log if "fog updated" in l)
summary.append(f"Nebel-Aktualisierungen der Karte: {fog_updates}")
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
ok = (not crash) and counts.get('r', 0) >= 6
sys.exit(0 if ok else 1)
PY
