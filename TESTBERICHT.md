# FoggyMap – Testbericht

Stand: 22.10.2026. Alle Ergebnisse stammen aus echten Läufen, die du jederzeit unter *Actions* im Repository nachprüfen kannst.

## ✅ Gebaut

| Was | Ergebnis |
|---|---|
| Android-Build (GitHub Actions, Ubuntu 24.04, JDK 17, SDK 35) | **erfolgreich**. Jeder Push erzeugt `FoggyMap.apk` (Release, signiert, ~40 MB) und `FoggyMap-debug.apk` |
| Signierung | eigener, stabiler Schlüssel. Updates lassen sich über die alte Version installieren |
| Download-Link | Release „FoggyMap – neueste Version“ (wird bei jedem Build ersetzt) |

## ✅ Automatisch getestet

**1. Kernlogik (37 Unit-Tests, laufen bei jedem Build)**
- Straßenaufteilung: Teilung an Kreuzungen, Fußwege teilen keine Straßen, Brücken bilden keine Kreuzung, Gehwege und Zufahrten werden ignoriert, lange Landstraßen werden gestückelt, die IDs sind kachelübergreifend stabil.
- Blockzuordnung: Ein Abschnitt deckt genau die 2 angrenzenden Blöcke auf, nicht den Block hinter der Parallelstraße und nicht die Kreuzungsfortsetzung. Riesenblöcke (24 ha) werden nur als Streifen aufgedeckt. Ein Fußweg im Park deckt nur einen Streifen auf. Bahnlinien begrenzen Blöcke. In Dörfern ohne Blöcke werden nur Häuser ohne dazwischenliegende Straße aufgedeckt.
- GPS-Jitter: 15 Zufallsläufe mit 0 Fehlaufdeckungen beim Stehen an einer Kreuzung (±7 m Streuung, 2,5 min), 0 Fehlaufdeckungen einer nur 20 m entfernten Parallelstraße und 0 verpasste Straßen. Ungenaue Positionen (±60 m) und GPS-Sprünge werden verworfen. Geräte, die Geschwindigkeit 0 melden, werden korrekt behandelt. Bei der Autofahrt mit nur alle 42 m einer Position werden die 35 m kurzen Abschnitte erkannt, ohne Seitenstraßen.
- Offline-Erkundung: Fortschritt übersteht den Neustart ohne Duplikate.
- Zusammenführung zweier Nutzer: Vereinigung ohne Verlust und ohne Duplikate, unabhängig von der Reihenfolge, Wiederholungen sind folgenlos.
- Echte OSM-Daten (Helsinki-Zentrum, 2.599 Abschnitte, 259 Blöcke): Plausibilitätsprüfung für 400 Abschnitte, ein Spaziergang deckt genau die richtige Straße auf. Nebelaktualisierung bei 2.500 aufgedeckten Flächen dauert höchstens ~0,15 s am PC.

**2. Koop-Server (10 Tests)**
Gruppencodes, idempotenter Upload, Zusammenführung zweier Spieler, Seitenweises Laden, 10 gleichzeitige Uploads ohne Duplikate, Abwehr ungültiger Eingaben, Ratenbegrenzung bei Code-Raten, Live-Position (Opt-in, Ablauf nach 10 min), HTTP-Ende-zu-Ende, Cloudflare-Worker-Code gegen D1-Semantik.
**Vertragstest:** 256 von der App-Logik erzeugte Elemente werden vom Server angenommen und unverändert zurückgegeben. Dieser Test hat einen echten Fehler gefunden, der behoben ist.
Zusätzlich lief der Worker lokal in der echten Cloudflare-Laufzeit (`wrangler dev`, workerd + lokale D1).

**3. Ende-zu-Ende im Android-Emulator (Android 14, bei jedem Build)**
Die APK wird installiert. Eine simulierte 1,1-km-GPS-Fahrt (6 m/s) führt über echte Helsinki-Straßen. Danach prüft der Test die Datenbank in der App, die Datenbank des Koop-Servers, Screenshots und Logs.
Letzter vollständiger Lauf: **kein Absturz der App**. Aufgedeckt wurden 10 Straßenabschnitte, 10 Häuserblöcke, 2 Teilflächen und 20 Gebäude. **42 von 42 Funden kamen auf dem Koop-Server an.** Die App hat dabei selbst eine Gruppe erstellt. Nachbarkacheln wurden live über Overpass geladen.
Die App hat im Vordergrund, im Hintergrund und bei ausgeschaltetem Bildschirm aufgezeichnet. Dabei zeigte sich, dass das gebündelte GPS-Liefern bei ausgeschaltetem Bildschirm auf dem Emulator nichts liefert. Die App erkennt das inzwischen nach ca. 1 Minute selbst und schaltet dauerhaft auf normale Lieferung um. Das ist im Emulator nachgewiesen („GPS batching delivered nothing – disabled“). Im späteren Lauf kamen 38 von 38 Funden auf dem Server an.
Die 3D-Karte selbst lässt sich im GitHub-Emulator nicht flüssig testen, weil er keine Grafikkarte hat (5–14 s pro Bild). Der Emulator-Test läuft deshalb nur noch auf Wunsch (Actions → FoggyMap APK → Run workflow → „Emulator-Test“).
Beim Wiedereinschalten des Bildschirms stürzte einmal der **OpenGL-Übersetzer des Emulators** ab (`libGLESv2_enc.so`, Software-Grafik ohne GPU). Das betrifft den Emulator. Ob echte Geräte betroffen sind, kann ich ohne Gerät nicht sicher sagen.

## ⚠️ Nicht getestet (konnte ich ohne echtes Handy nicht prüfen)

- **Echtes Gerät, echtes GPS draußen**, insbesondere in Häuserschluchten, unter Bäumen und im Auto.
- **Akkuverbrauch in Prozent pro Stunde**. Die Sparmaßnahmen sind umgesetzt, aber nicht gemessen.
- **Hersteller-Energiesparmodi** (Samsung, Xiaomi, Huawei …), die Hintergrunddienste beenden können.
- **Cloudflare-Einrichtung mit deinem Konto**. Workflow und Worker sind getestet, die Einrichtung mit deinem Konto nicht.
- **Zwei echte Handys gleichzeitig.** Getestet sind App ↔ echter Server im Emulator sowie zwei Spieler in den Server-Tests.
- Andere Android-Versionen als 14 (Mindestversion 8.0) und schwache Geräte.
- Die Darstellung der Hintergrundkarte (OpenFreeMap-Kacheln) im Emulator: Die Software-Grafik im CI ist sehr langsam.

## Bekannte Grenzen

- Die öffentlichen Overpass-Server sind zeitweise überlastet (Fehler 502/504). Die App wiederholt den Download dann automatisch, und deine Wege gehen nicht verloren. Das erste Laden einer neuen Gegend kann aber einige Minuten dauern.
- Die Qualität hängt von OpenStreetMap ab. Fehlende Straßen oder Gebäude in OSM fehlen auch in FoggyMap.
- Sehr lange Tunnel werden nicht aufgedeckt (kein GPS-Empfang). Brücken nur, wenn man darüber fährt.
- Die Prozentangabe bezieht sich auf die geladenen Kartenkacheln um deine Wege („deine Umgebung“), nicht auf eine ganze Stadt.
