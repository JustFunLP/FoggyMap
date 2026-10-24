# FoggyMap

Android-App: Die Welt liegt unter Nebel, und nur wo du selbst unterwegs warst, wird die Karte sichtbar.
Sie deckt immer ganze Straßenabschnitte bis zur nächsten Kreuzung auf, zusammen mit den angrenzenden Häuserblöcken, und zeigt sie als dunkle 3D-Stadtkarte.
Im Koop-Modus erkundet ihr zu mehreren dieselbe Karte, direkt aus der App heraus, ohne eigenen Server.

**➡ Bedienung, Installation und Koop (nur mit dem Handy): [ANLEITUNG.md](ANLEITUNG.md)**

---

## Aufbau

```
app/src/main/java/de/foggymap/app/
  core/        reine Kotlin-Logik (ohne Android, per Unit-Test geprüft)
    RoadNetwork.kt   OSM-Wege → Straßenabschnitte zwischen echten Kreuzungen
    WorldModel.kt    Häuserblöcke (planarer Graph), Aufdeck-Regeln, Gebäude
    MapMatcher.kt    GPS → Straßenabschnitt (Filter, Richtung, Entprellung)
    Explorer.kt      Kartenzellen + Matching + Aufdecken, offline-fähig
    Mvt.kt, TileNetwork.kt  Vektorkachel-Decoder und Straßennetz aus Kacheln
    FogBuilder.kt    Vereinigung aller Funde → Nebel-GeoJSON (inkrementell)
    RevealSet.kt     Grow-only-Set (konfliktfreie Zusammenführung)
    Invite.kt        Gruppencodes und Einladungstexte
    Schnorr.kt, Nostr.kt  Nostr-Signaturen (BIP-340), Ereignisse, Gruppenverschlüsselung
  data/        SQLite-Speicher, Kachel-Download + Offline-Speicher, Koop-Sync
  tracking/    Vordergrunddienst, akkusparende Standortsteuerung
  ui/          Jetpack Compose + MapLibre (3D-Karte, Nebel, Animationen)
server/        optionaler eigener Koop-Server: Cloudflare Worker (D1) und Node.js (SQLite)
scripts/       Emulator-End-to-End-Test (simulierte GPS-Route)
.github/workflows/
  android.yml  Unit-Tests → signierte FoggyMap.apk → Release-Link → Emulator-Test
  server.yml   optional: eigenen Koop-Server per Knopfdruck auf Cloudflare einrichten
```

## Wie das Aufdecken funktioniert

1. **Kartendaten:** Straßen, Bahnlinien, Flüsse und Gebäude kommen aus denselben Vektorkacheln (OpenFreeMap, Zoomstufe 14, ca. 1,5 × 1,5 km), die auch die Karte zeigt. Eine Kachel hat 50–500 KB und kommt vom CDN in Sekundenbruchteilen. Das Straßennetz einer Zelle wird aus ihrer Kachel und den 8 Nachbarkacheln gebaut. Beim Erkunden lädt die App die Umgebung im Hintergrund (WLAN: ca. 8 km, mobil: ca. 3 km), in den Einstellungen auch die ganze Stadt (12 km). Alles bleibt offline gespeichert. Höchstens 4 parallele Anfragen und eine eigene App-Kennung, wie OpenFreeMap es für Offline-Downloads verlangt.
2. **Straßenabschnitte:** Vektorkacheln haben keine OSM-Knoten und schneiden Linien am Kachelrand ab. Die App fügt die Stücke über die Kachelränder wieder zusammen und findet Kreuzungen geometrisch: gemeinsame Punkte, Einmündungen (T) und Kreuzungen auf gleicher Ebene. Brücken und Tunnel verbinden sich nur an ihren Enden. Straßenstücke, die nur weiterlaufen (in OSM z. B. wegen eines Belagwechsels geteilt), werden verbunden, sodass ein Abschnitt wirklich von Kreuzung zu Kreuzung reicht. Einmündende Fußwege teilen eine Straße nicht. Sehr lange Landstraßen werden nach 900 m gestückelt. Separat eingezeichnete Gehwege (laufen 1–16 m neben einer Straße in gleicher Richtung), Zebrastreifen, Zufahrten und Parkplatzgassen werden ignoriert. Die ID eines Abschnitts wird aus den Koordinaten der Endkreuzungen berechnet und ist auf allen Handys und in allen Zellen gleich. Abgleich mit Overpass (Odenthal, Berlin): 100 % der Kreuzungen gefunden, keine Straße fehlt, Blockanzahl ±3 %.
3. **Häuserblöcke:** Alle Fahrstraßen ohne Brücken und Tunnel, dazu Bahnlinien und Flüsse, werden verknotet (JTS `UnaryUnion`) und polygonisiert. Die Flächen dazwischen sind die Blöcke.
   Ein Block gilt als angrenzend, wenn **mindestens die Hälfte des Abschnitts (max. 25 m) auf seinem Rand liegt**. So bleiben Blöcke, die nur die Kreuzung berühren, verdeckt. Dasselbe gilt für Blöcke hinter einer Parallelstraße.
   Sicherheitsnetze: Blöcke > 5 ha (Industrie, Parks, Felder) und Blöcke am unsicheren Kachelrand werden nur als 50 m tiefer Streifen aufgedeckt. Liegt auf einer Straßenseite gar kein Block (See, Feld, Ortsrand), wird dort ein 30-m-Streifen sichtbar. Ein Fußweg in einem Park deckt einen 25-m-Streifen auf. Gibt es keine geschlossenen Blöcke (Dorf, Landstraße), werden die Häuser bis 30 m neben der Straße sichtbar. Streifen reichen nie über eine andere Straße hinweg.
   **Wählbar im Menü → Erkunden:** *Häuserblöcke* (wie oben), *Straßenzug* (Straße plus Streifen 15–100 m je Seite) oder *Umkreis* (Radius 20–250 m um den GPS-Weg, entsteht sofort aus den GPS-Punkten, auch ohne Kartendaten).
   Aufgedeckte Blöcke werden 3 m nach innen versetzt, damit fremde Randstraßen im Nebel bleiben.
4. **GPS-Zuordnung:** Positionen ungenauer als ±35 m und Sprünge über 250 km/h werden verworfen. Gesucht wird in einem Radius, der von der Genauigkeit abhängt. Die Bewertung berücksichtigt den Abstand, die Bewegungsrichtung gegenüber der Straßenachse und die Fortsetzung des bisherigen Abschnitts.
   Ist ein anderer Abschnitt fast gleich gut (Parallelstraße, Kreuzung), zählt nichts. Ein Abschnitt braucht mindestens zwei Belege, und in der Kreuzungszone zählt nur eindeutige Bewegung entlang der Straße.
   Im Stand wird über die letzten 20 s gemittelt, damit GPS-Zittern an einer Kreuzung keine Seitenstraßen freischaltet. Schnelle Fahrten werden zwischen zwei Positionen interpoliert, damit auch 30 m kurze Abschnitte erkannt werden.
5. **Offline / Sofortanzeige:** Dein Weg erscheint sofort als heller Lichtstreifen, auch bevor die Straße zugeordnet ist. Positionen ohne geladene Kartendaten landen in einer gespeicherten Warteschlange und werden in zeitlicher Reihenfolge nachträglich ausgewertet. Mit der alten Datenquelle aufgedeckte Straßen werden geometrisch wiedererkannt und nicht doppelt gezählt.

## Koop-Synchronisierung

- Alle Funde bilden ein **Grow-only-Set** (CRDT). Zusammenführen ist eine reine Vereinigung über die ID. Doppelte Uploads, Wiederholungen nach Netzfehlern und gleichzeitige Uploads mehrerer Spieler können nichts löschen und nichts doppelt anlegen. Das ist mit Tests belegt.
- **Standard: ohne Server über Nostr-Relays.** „Gruppe erstellen“ erzeugt den 12-stelligen Code (60 Bit Zufall) auf dem Handy. Jedes Mitglied hat einen eigenen, zufälligen Signierschlüssel (BIP-340, nur auf dem Gerät) und veröffentlicht seine Funde auf fünf öffentlichen Relays gleichzeitig (`wss://`, also TLS):
  - Funde: ersetzbare App-Daten-Ereignisse (NIP-78, Kind 30078), je ca. 40 KB JSON, komprimiert und verschlüsselt ca. 13 KB. Neue Funde ändern nur das letzte Paket, deshalb wird pro Sync meist nur ein Paket neu gesendet.
  - Mitgliedskarte: Anzeigename, Gruppenname, Kilometer. Wird nur bei Änderung oder alle 6 Stunden gesendet.
  - Live-Standort: nur nach ausdrücklichem Einschalten. Dann Position, Blickrichtung und die neuesten Funde als *flüchtige* Ereignisse (Kind 20078, NIP-16) über offene Verbindungen zu 3 der 5 Relays (zwei beliebige 3er-Auswahlen aus 5 überschneiden sich immer): alle 2–3 Sekunden, solange ein Mitglied die Karte offen hat (es sendet alle 30 s ein flüchtiges „schaut zu“, Kind 20079), sonst alle 20 s. Relays leiten sie nur weiter und speichern sie nicht. Zusätzlich alle 3 Minuten ein gespeicherter Stand mit Ablaufzeit 10 Minuten (NIP-40) für alle, die die App gerade erst öffnen.
  - **Verschlüsselung:** Aus dem Gruppencode werden per PBKDF2-SHA256 (60.000 Runden) ein öffentliches Such-Kennzeichen und ein AES-256-GCM-Schlüssel abgeleitet. Die Relays sehen nur das Kennzeichen, Zeitstempel und unlesbare Daten. Wer den Code nicht kennt, kann nichts lesen und nichts unterschieben (fremde Pakete lassen sich nicht entschlüsseln und werden verworfen).
  - Sync gebündelt: alle 2–3 Minuten während der Erkundung, beim Öffnen der App und bei Netzwechsel. Abgeholt wird inkrementell ab dem letzten Zeitstempel.
- **Optional: eigener Server** (Cloudflare Worker + D1 oder Node.js + SQLite, siehe ANLEITUNG Abschnitt 4). Dann gilt: 12-stellige Gruppencodes, Ratenbegrenzung, Eingabeprüfung, Größenlimits und nur HTTPS. Mitglieds-IDs werden gehasht.
- Geteilt werden nur aufgedeckte Flächen, Kilometerstände und der Anzeigename. Der **Live-Standort ist standardmäßig aus**. Er wird nur nach ausdrücklicher Bestätigung übertragen und verfällt nach 10 Minuten.

## Fortbewegung

- `TripModeClassifier` (Unit-Tests) entscheidet aus den umliegenden Minuten, nicht aus einem Moment: Zug, wenn die bewegten Positionen überwiegend genau auf einer Bahnstrecke liegen (< 20 m, Straße mind. 15 m weiter weg) und Zuggeschwindigkeit vorkommt. Auto, wenn innerhalb von 3 min davor oder 90 s danach über ~34 km/h gefahren wird. Rad bei Geschwindigkeiten über ~16 km/h, sonst zu Fuß (auch Joggen). Jede Strecke wird 90 s zurückgehalten, damit der Ampelstart sie noch richtig zuordnet.
- Von Hand wählbar (Knopf auf der Karte, gilt bis zum Ende der Tour): Auto passt nur auf befahrbare Straßen.
- **Im Zug** wird ein 80 m breiter Streifen entlang der Gleise aufgedeckt: Jede Position wird auf die nächste Bahnlinie (bis 35 m) verschoben und zu einem durchgehenden Band verbunden, Häuser darin erscheinen in 3D. Wird der Zug erst nach einer Weile erkannt, wird die Strecke der letzten 5 Minuten nachträglich mit aufgedeckt. Straßenkilometer zählen im Zug nicht.
- Die Aktivitätserkennung des Handys (Fahrzeug / Rad / zu Fuß) entscheidet Fälle, die das Tempo allein nicht kann: Auto im Stau, langsames Rad, schneller Läufer (Unit-Tests).
- Die Figur auf der Karte folgt der Fortbewegung (Läufer, Radfahrer, Auto, Zug, jeweils von der Seite, von vorn und von hinten, animiert). Freunde schicken ihre Fortbewegung im Live-Kanal mit.
- **Figur auf der Straße** (`RoadSnapper`, Unit-Tests, nur Anzeige): Die Figur steht auf der Mittellinie der Straße, auf der du bist, statt dort, wo das GPS gerade hinwackelt. Eingerastet wird nur, wenn eine Straße nah genug ist (15 m bzw. die GPS-Genauigkeit bis 25 m; verlassen erst bei 8 m mehr). Eine andere Straße übernimmt nur, wenn sie deutlich besser passt und zur Bewegungsrichtung passt. Zwischen zwei Positionen gleitet die Figur mit deinem Tempo die Straße entlang (auch geradeaus über die Kreuzung) und tritt nie ein paar Meter zurück, nur weil eine Position spät kam. Im Zug steht die Figur auf den Gleisen.

## Fehlersuche

- **Problembericht** (Menü → App): Gerät, Android, Einstellungen, GPS-Qualität der Tour (Median, 90 %, Anteil zu ungenau, Zeit je GPS-Stufe), Akku %/h (ganzes Handy, nur ohne Ladekabel, gesamt und mit Live-Standort), Kartencache, Koop-Status, Abstürze und das eigene Protokoll der App (`logcat --pid`). Bereinigt durch `ReportSanitizer` (Unit-Test): keine Koordinaten, Kachelnummern, Straßen- oder Personennamen, Gruppencodes, Schlüssel. Geteilt als Textdatei über einen FileProvider, nur nach Vorschau.
- **Absturzberichte:** Java/Kotlin-Abstürze schreibt ein Handler vor dem Beenden in eine Datei. Native Abstürze (z. B. Kartengrafik) und Einfrieren (ANR) kommen aus Androids Exit-Historie (ab Android 11). Beim nächsten Start bietet die App einmal pro Vorfall an, den Bericht zu teilen.

## Abzeichen

- Ein Abzeichen wird durch ein öffentliches, vom Admin signiertes Nostr-Ereignis vergeben (Kind 30078, `#t fm-badges`, ersetzbar je Abzeichen und Empfänger, also auch zurücknehmbar). Ziele: *alle Installationen bis Zeitpunkt T* (`firstInstallTime`), *Spieler-ID* (40 Bit des eigenen Geräte-Schlüssels, `XXXX-XXXX`) oder ein *Gruppenmitglied* (Schlüssel).
- Die App enthält nur den öffentlichen Admin-Schlüssel (`Badges.ISSUER`) und prüft jede Signatur. Der private Schlüssel entsteht per PBKDF2 (120 000 Runden) aus dem Admin-Code, der nur beim Admin liegt.
- Neue Abzeichen kann der Admin in der App anlegen (Name, Text, Symbol, Farbe). Die eingebauten (z. B. „Alpha Tester“) haben eigenes Design.
- Beim App-Start werden neue Abzeichen abgeholt. Jedes erscheint einmal mit einer großen Animation: Lichtstrahlen, die Medaille dreht sich herein, Konfetti, Glanz und ein kurzes Vibrieren.

## Akku

Wie bei Google Maps und Waze: Standort vom **Google Fused Location Provider** (Play-Dienste; ohne Play-Dienste Androids eigener Standortdienst) und **Aktivitätserkennung** (Activity Recognition Transition API, läuft auf dem stromsparenden Sensor-Chip, Berechtigung „Körperliche Aktivität“, optional).

| Maßnahme | Wirkung |
|---|---|
| **Aktivitätserkennung meldet „steht“ und das GPS bestätigt es (20 s ohne Bewegung nach der Meldung, falls der Sensor sich irrt) → GPS ganz aus.** Wieder an, sobald der Sensor „geht / Rad / Fahrzeug“ meldet oder der Bewegungssensor auslöst. Im Stand nur kostenlose passive Positionen und als Notbremse alle 4 min eine einzelne GPS-Messung | größter Effekt: GPS an jeder Ampel, im Café, im Büro aus |
| Ohne Aktivitätserkennung: nach ~30 s Stillstand alle 10 s, nach ~2,5 min aus (an oft besuchten Orten nach ~40 s), Stichprobe alle 1,5 min | wie bisher |
| Kein GPS-Empfang seit 3 min (drinnen) → aus | verhindert stundenlange Satellitensuche |
| Update-Rate je nach Tempo (zu Fuß alle 3 s, Auto alle 2 s) | genug Punkte auch für kurze Abschnitte |
| Bildschirm aus → gebündelte Lieferung (alle 20–30 s ein Paket), außer jemand aus der Gruppe schaut gerade live zu | Hauptprozessor schläft dazwischen |
| **Live-Standort:** nur 3 statt 5 Relay-Verbindungen; alle 2–3 s nur, solange jemand die Karte offen hat (Signal „schaut zu“ alle 30 s), sonst alle 20 s / im Stand 60 s. Gespeicherter Live-Stand alle 3 statt jede Minute | Funkmodul kann zwischen den Sendungen schlafen |
| Karte höchstens 30 Bilder/s (60 nur beim Wischen), Figur-Animation ~5 Bilder/s, kein „atmender“ Nebelrand mehr (zeichnete die 3D-Karte 3×/s neu), Kompass-Zittern unter 3° dreht die Karte nicht | weniger GPU bei eingeschaltetem Bildschirm |
| Im Hintergrund wird nichts gezeichnet oder neu berechnet; Nebel höchstens alle 4 s neu aufgebaut | weniger CPU |
| Kartendaten einmalig pro Kachel, Sync gebündelt, keine Wakelocks | weniger Funk und CPU |

Es gibt keine Akku- oder GPS-Einstellungen: Alles oben passiert automatisch. Was das GPS gerade tut, steht unter Menü → Info.

## Build

Der Build läuft vollständig über GitHub Actions (`.github/workflows/android.yml`): JDK 17, Android SDK 35, Gradle 8.11.1, AGP 8.7.3, Kotlin 2.0.21, Compose BOM 2024.12, MapLibre Android 11.8.0, JTS 1.20.
Ergebnis: **`FoggyMap.apk`** (Release, signiert) als Release „FoggyMap – neueste Version“ und als Artefakt. Dazu kommt `FoggyMap-debug.apk` für Tests.

**Signierung:** Beim ersten Build erzeugt der Workflow einen eigenen Schlüssel. Er wird in einem **Entwurfs-Release** gespeichert, das nur Repository-Mitglieder sehen. Der Schlüssel landet nie im Git-Verlauf. Alternativ können die Secrets `FOGGY_KEYSTORE_B64` und `FOGGY_KEYSTORE_PASSWORD` gesetzt werden.
**Wichtig:** Den Entwurf „Signierschlüssel“ nicht löschen. Sonst lassen sich neue Versionen nicht mehr über die alte installieren.

Lokal (mit PC): `gradle :app:testDebugUnitTest :app:assembleRelease`. Server: `cd server && npm test`.

## Datenquellen und Lizenzen

Karten- und Straßendaten © OpenStreetMap-Mitwirkende (ODbL). Vektorkacheln von OpenFreeMap / OpenMapTiles.
OpenFreeMap erlaubt App-Downloads für die Offline-Nutzung bis 3.000 Kacheln bzw. 50 MB pro Download mit höchstens 4 parallelen Anfragen. FoggyMap hält sich daran. Für eine breite Veröffentlichung sollte man mit OpenFreeMap sprechen oder eigene Kacheln hosten.
Kartendarstellung: MapLibre Native (BSD). Geometrie: JTS (EPL/EDL). Die Testdaten in `app/src/test/resources` sind ein OSM-Auszug von Helsinki (ODbL).
