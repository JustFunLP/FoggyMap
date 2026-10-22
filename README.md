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
    Explorer.kt      Kartenkacheln + Matching + Aufdecken, offline-fähig
    FogBuilder.kt    Vereinigung aller Funde → Nebel-GeoJSON (inkrementell)
    RevealSet.kt     Grow-only-Set (konfliktfreie Zusammenführung)
    Invite.kt        Gruppencodes und Einladungstexte
  data/        SQLite-Speicher, Overpass-Download + Offline-Cache, Koop-Sync
  tracking/    Vordergrunddienst, akkusparende Standortsteuerung
  ui/          Jetpack Compose + MapLibre (3D-Karte, Nebel, Animationen)
server/        Koop-Server: gemeinsame Logik für Cloudflare Worker (D1) und Node.js (SQLite)
scripts/       Emulator-End-to-End-Test (simulierte GPS-Route)
.github/workflows/
  android.yml  Unit-Tests → signierte FoggyMap.apk → Release-Link → Emulator-Test
  server.yml   Koop-Server per Knopfdruck auf Cloudflare einrichten
```

## Wie das Aufdecken funktioniert

1. **Kartendaten:** Für eine Kachel von ca. 0,9 × 0,9 km (plus 350 m Rand) lädt die App über die Overpass-API alle Straßen, Bahnlinien, Flüsse und Gebäude. Die Kachel wird dauerhaft gespeichert und funktioniert danach offline.
2. **Straßenabschnitte:** Ein OSM-Weg wird an jedem Knoten geteilt, den er mit einer anderen *Straße* teilt. Einmündende Fußwege teilen eine Straße nicht. Brücken und Tunnel kreuzen ohne gemeinsamen Knoten und bilden deshalb keine Kreuzung. Sehr lange Landstraßen werden nach 900 m gestückelt. Gehwege (`footway=sidewalk`), Zufahrten und private Wege werden ignoriert. Die ID eines Abschnitts (Weg + Endknoten) ist kachelübergreifend stabil.
3. **Häuserblöcke:** Alle Fahrstraßen ohne Brücken und Tunnel, dazu Bahnlinien und Flüsse, werden verknotet (JTS `UnaryUnion`) und polygonisiert. Die Flächen dazwischen sind die Blöcke.
   Ein Block gilt als angrenzend, wenn **mindestens die Hälfte des Abschnitts (max. 25 m) auf seinem Rand liegt**. So bleiben Blöcke, die nur die Kreuzung berühren, verdeckt. Dasselbe gilt für Blöcke hinter einer Parallelstraße.
   Sicherheitsnetze: Blöcke > 5 ha (Industrie, Parks, Felder) und Blöcke am unsicheren Kachelrand werden nur als 40 m tiefer Streifen aufgedeckt. Ein Fußweg in einem Park deckt einen 25-m-Streifen auf. Gibt es keine geschlossenen Blöcke (Dorf, Landstraße), werden die Häuser bis 30 m neben der Straße sichtbar, aber nur, wenn keine andere Straße dazwischen liegt.
   Aufgedeckte Blöcke werden 3 m nach innen versetzt, damit fremde Randstraßen im Nebel bleiben.
4. **GPS-Zuordnung:** Positionen ungenauer als ±35 m und Sprünge über 250 km/h werden verworfen. Gesucht wird in einem Radius, der von der Genauigkeit abhängt. Die Bewertung berücksichtigt den Abstand, die Bewegungsrichtung gegenüber der Straßenachse und die Fortsetzung des bisherigen Abschnitts.
   Ist ein anderer Abschnitt fast gleich gut (Parallelstraße, Kreuzung), zählt nichts. Ein Abschnitt braucht mindestens zwei Belege, und in der Kreuzungszone zählt nur eindeutige Bewegung entlang der Straße.
   Im Stand wird über die letzten 20 s gemittelt, damit GPS-Zittern an einer Kreuzung keine Seitenstraßen freischaltet. Schnelle Fahrten werden zwischen zwei Positionen interpoliert, damit auch 30 m kurze Abschnitte erkannt werden.
5. **Offline:** Positionen ohne geladene Kartenkachel landen in einer gespeicherten Warteschlange und werden später in zeitlicher Reihenfolge nachträglich ausgewertet.

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

Karten- und Straßendaten © OpenStreetMap-Mitwirkende (ODbL). Vektorkacheln von OpenFreeMap / OpenMapTiles. Straßenabfrage über die öffentliche Overpass-API.
Die öffentlichen Dienste haben Fair-Use-Regeln. Für private Nutzung in kleinen Gruppen ist das unproblematisch, für eine breite Veröffentlichung sollte ein eigener Overpass- bzw. Kachelserver eingerichtet werden.
Kartendarstellung: MapLibre Native (BSD). Geometrie: JTS (EPL/EDL). Die Testdaten in `app/src/test/resources` sind ein OSM-Auszug von Helsinki (ODbL).
