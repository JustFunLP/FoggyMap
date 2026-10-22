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

adb wait-for-device
adb shell settings put secure location_mode 3 || true
# A software-rendered CI emulator is slow; never show "isn't responding" dialogs over the app.
adb shell settings put global hide_error_dialogs 1 || true
adb shell settings put global anr_show_background 0 || true
adb install -r -g out/FoggyMap-debug.apk
adb shell pm grant $PKG android.permission.ACCESS_FINE_LOCATION || true
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS || true
adb logcat -c

read -r LON LAT < scripts/e2e_route.txt
for i in 1 2 3; do adb emu geo fix "$LON" "$LAT"; sleep 1; done

adb shell am start -n $PKG/de.foggymap.app.MainActivity --ez e2e_autostart true
# Wait until the street data of the start cell is loaded (Overpass can take a while).
for i in $(seq 1 90); do
  adb emu geo fix "$LON" "$LAT" > /dev/null
  if adb logcat -d -s FoggyMap:I | grep -q "cell loaded"; then echo "map data loaded after ${i}x2 s"; break; fi
  sleep 2
done
sleep 5
shot 01-start

# Drive the route: one GPS fix per second (≈ 6 m/s).
n=0
while read -r LON LAT; do
  adb emu geo fix "$LON" "$LAT" > /dev/null
  n=$((n+1))
  if [ $n -eq 60 ]; then shot 02-unterwegs; fi
  sleep 1
done < scripts/e2e_route.txt
# Stand still at the end for a while (exercises the stationary/battery logic).
for i in $(seq 1 20); do adb emu geo fix "$LON" "$LAT" > /dev/null; sleep 1; done
sleep 8
shot 03-ziel

# Statistics sheet: tap the bottom card (screen-size independent: 80 % height, centre).
SIZE=$(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | tail -1); W=${SIZE%x*}; H=${SIZE#*x}
adb shell input tap $((W/2)) $((H*76/100)); sleep 3; shot 04-statistik
adb shell input keyevent KEYCODE_BACK; sleep 2
adb shell input tap $((W*73/100)) $((H*85/1000)); sleep 3; shot 05-koop
adb shell input keyevent KEYCODE_BACK; sleep 1

adb exec-out run-as $PKG cat databases/foggymap.db > "$OUT/foggymap.db" 2>/dev/null || true
adb exec-out run-as $PKG cat databases/foggymap.db-wal > "$OUT/foggymap.db-wal" 2>/dev/null || true
adb logcat -d > "$OUT/logcat-full.txt"
grep -E "FoggyMap|FoggyMatcher|AndroidRuntime|FATAL|maplibre|MapLibre|foggymap" "$OUT/logcat-full.txt" > "$OUT/logcat-app.txt" || true

python3 - "$OUT" <<'PY'
import sqlite3, sys, os, shutil
out = sys.argv[1]
db = os.path.join(out, "foggymap.db")
summary = []
crash = "FATAL EXCEPTION" in open(os.path.join(out, "logcat-full.txt"), errors="ignore").read()
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
open(os.path.join(out, "summary.txt"), "w").write("\n".join(summary) + "\n")
print("\n".join(summary))
ok = (not crash) and counts.get('r', 0) >= 10
sys.exit(0 if ok else 1)
PY
