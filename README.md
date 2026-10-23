# FoggyMap

Android-App: Die Welt liegt unter Nebel, und nur wo du selbst unterwegs warst, wird die Karte sichtbar.
Sie deckt immer ganze Straßenabschnitte bis zur nächsten Kreuzung auf, zusammen mit den angrenzenden Häuserblöcken, und zeigt sie als dunkle 3D-Stadtkarte.
Im Koop-Modus erkundet ihr zu mehreren dieselbe Karte.

**➡ Bedienung, Installation und Server-Einrichtung (nur mit dem Handy): [ANLEITUNG.md](ANLEITUNG.md)**

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
  data/        SQLite-Speicher, Kachel-Download + Offline-Speicher, Koop-Sync
  tracking/    Vordergrunddienst, akkusparende Standortsteuerung
  ui/          Jetpack Compose + MapLibre (3D-Karte, Nebel, Animationen)
server/        Koop-Server: gemeinsame Logik für Cloudflare Worker (D1) und Node.js (SQLite)
scripts/       Emulator-End-to-End-Test (simulierte GPS-Route)
.github/workflows/
  android.yml  Unit-Tests → signierte FoggyMap.apk → Release-Link → Emulator-Test
  server.yml   Koop-Server per Knopfdruck auf Cloudflare einrichten
```

## Wie das Aufdecken funktioniert

1. **Kartendaten:** Straßen, Bahnlinien, Flüsse und Gebäude kommen aus denselben Vektorkacheln (OpenFreeMap, Zoomstufe 14, ca. 1,5 × 1,5 km), die auch die Karte zeigt. Eine Kachel hat 50–500 KB und kommt vom CDN in Sekundenbruchteilen. Das Straßennetz einer Zelle wird aus ihrer Kachel und den 8 Nachbarkacheln gebaut. Beim Erkunden lädt die App die Umgebung im Hintergrund (WLAN: ca. 8 km, mobil: ca. 3 km), in den Einstellungen auch die ganze Stadt (12 km). Alles bleibt offline gespeichert. Höchstens 4 parallele Anfragen und eine eigene App-Kennung, wie OpenFreeMap es für Offline-Downloads verlangt.
2. **Straßenabschnitte:** Vektorkacheln haben keine OSM-Knoten und schneiden Linien am Kachelrand ab. Die App fügt die Stücke über die Kachelränder wieder zusammen und findet Kreuzungen geometrisch: gemeinsame Punkte, Einmündungen (T) und Kreuzungen auf gleicher Ebene. Brücken und Tunnel verbinden sich nur an ihren Enden. Straßenstücke, die nur weiterlaufen (in OSM z. B. wegen eines Belagwechsels geteilt), werden verbunden, sodass ein Abschnitt wirklich von Kreuzung zu Kreuzung reicht. Einmündende Fußwege teilen eine Straße nicht. Sehr lange Landstraßen werden nach 900 m gestückelt. Separat eingezeichnete Gehwege (laufen 1–16 m neben einer Straße in gleicher Richtung), Zebrastreifen, Zufahrten und Parkplatzgassen werden ignoriert. Die ID eines Abschnitts wird aus den Koordinaten der Endkreuzungen berechnet und ist auf allen Handys und in allen Zellen gleich. Abgleich mit Overpass (Odenthal, Berlin): 100 % der Kreuzungen gefunden, keine Straße fehlt, Blockanzahl ±3 %.
3. **Häuserblöcke:** Alle Fahrstraßen ohne Brücken und Tunnel, dazu Bahnlinien und Flüsse, werden verknotet (JTS `UnaryUnion`) und polygonisiert. Die Flächen dazwischen sind die Blöcke.
   Ein Block gilt als angrenzend, wenn **mindestens die Hälfte des Abschnitts (max. 25 m) auf seinem Rand liegt**. So bleiben Blöcke, die nur die Kreuzung berühren, verdeckt. Dasselbe gilt für Blöcke hinter einer Parallelstraße.
   Sicherheitsnetze: Blöcke > 5 ha (Industrie, Parks, Felder) und Blöcke am unsicheren Kachelrand werden nur als 40 m tiefer Streifen aufgedeckt. Ein Fußweg in einem Park deckt einen 25-m-Streifen auf. Gibt es keine geschlossenen Blöcke (Dorf, Landstraße), werden die Häuser bis 30 m neben der Straße sichtbar, aber nur, wenn keine andere Straße dazwischen liegt.
   Aufgedeckte Blöcke werden 3 m nach innen versetzt, damit fremde Randstraßen im Nebel bleiben.
4. **GPS-Zuordnung:** Positionen ungenauer als ±35 m und Sprünge über 250 km/h werden verworfen. Gesucht wird in einem Radius, der von der Genauigkeit abhängt. Die Bewertung berücksichtigt den Abstand, die Bewegungsrichtung gegenüber der Straßenachse und die Fortsetzung des bisherigen Abschnitts.
   Ist ein anderer Abschnitt fast gleich gut (Parallelstraße, Kreuzung), zählt nichts. Ein Abschnitt braucht mindestens zwei Belege, und in der Kreuzungszone zählt nur eindeutige Bewegung entlang der Straße.
   Im Stand wird über die letzten 20 s gemittelt, damit GPS-Zittern an einer Kreuzung keine Seitenstraßen freischaltet. Schnelle Fahrten werden zwischen zwei Positionen interpoliert, damit auch 30 m kurze Abschnitte erkannt werden.
5. **Offline / Sofortanzeige:** Dein Weg erscheint sofort als Leuchtspur, auch bevor die Straße zugeordnet ist. Positionen ohne geladene Kartendaten landen in einer gespeicherten Warteschlange und werden in zeitlicher Reihenfolge nachträglich ausgewertet. Mit der alten Datenquelle aufgedeckte Straßen werden geometrisch wiedererkannt und nicht doppelt gezählt.

## Koop-Synchronisierung

- Alle Funde bilden ein **Grow-only-Set** (CRDT). Zusammenführen ist eine reine Vereinigung über die ID. Doppelte Uploads, Wiederholungen nach Netzfehlern und gleichzeitige Uploads mehrerer Spieler können nichts löschen und nichts doppelt anlegen. Das ist mit Tests belegt.
- Die App speichert zuerst lokal (Outbox) und synchronisiert, wenn Netz da ist: nach neuen Funden (gebündelt), alle 3 Minuten während der Erkundung, beim Öffnen der App und bei Netzwechsel. Der Download läuft inkrementell über eine Server-Revision.
- Server: 12-stellige Gruppencodes mit 60 Bit Zufall, Ratenbegrenzung (allgemein, für neue Gruppen und streng für falsche Codes), Eingabeprüfung, Größenlimits und nur HTTPS. Mitglieds-IDs werden gehasht.
- Geteilt werden nur aufgedeckte Flächen, Kilometerstände und der Anzeigename. Der **Live-Standort ist standardmäßig aus**. Er wird nur nach ausdrücklicher Bestätigung übertragen und verfällt nach 10 Minuten.

## Akku

| Maßnahme | Wirkung |
|---|---|
| GPS aus nach ~2,5 min Stillstand. Aufwecken über den Bewegungssensor, passive Positionen und kurze GPS-Stichproben alle 45 s | größter Effekt beim Stehen und Sitzen |
| Kein GPS-Empfang seit 3 min (drinnen) → Pause | verhindert stundenlange Satellitensuche |
| Update-Rate je nach Tempo (zu Fuß 3 s / 4 m, Auto 2 s / 10 m) | weniger Aufwachvorgänge |
| Bildschirm aus → gebündelte Lieferung durch den GPS-Chip (Android 12+) | Hauptprozessor wacht nur alle 15–30 s auf |
| Kartendaten einmalig pro Kachel, Sync gebündelt, keine Wakelocks | weniger Funk und CPU |
| Karte animiert nur im Vordergrund. Kein dauerhaft pulsierender Positionspunkt | weniger GPU |

Drei Profile in den Einstellungen: *Präzise*, *Ausgewogen* (Standard) und *Sparsam*.

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
