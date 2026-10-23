# FoggyMap – Testbericht

Stand: 23.10.2026. Alle Ergebnisse stammen aus echten Läufen, die du jederzeit unter *Actions* im Repository nachprüfen kannst.

## ✅ Gebaut

| Was | Ergebnis |
|---|---|
| Android-Build (GitHub Actions, Ubuntu 24.04, JDK 17, SDK 35) | **erfolgreich**. Jeder Push erzeugt `FoggyMap.apk` (Release, signiert, ~40 MB) und `FoggyMap-debug.apk` |
| Signierung | eigener, stabiler Schlüssel. Updates lassen sich über die alte Version installieren |
| Download-Link | Release „FoggyMap – neueste Version“ (wird bei jedem Build ersetzt). Das Repository ist öffentlich, deshalb funktioniert der Link ohne Anmeldung |
| Selbst-Update | Die Update-Infos (`version.json`) und die APK sind ohne Anmeldung abrufbar. Die Prüfsumme der heruntergeladenen APK stimmt mit `version.json` überein (geprüft am 22.10.2026) |
| Geheimnisse | Der Signierschlüssel liegt nur in einem Entwurfs-Release, das ausschließlich Repo-Mitglieder sehen. Das Passwort wird in jeder Workflow-Version vor der Weitergabe maskiert und nirgends ausgegeben. Fremde Forks und Pull-Requests können den Build nicht auslösen |

## ✅ Automatisch getestet

**1. Kernlogik (78 Unit-Tests, laufen bei jedem Build)**
- Straßenaufteilung: Teilung an Kreuzungen, Fußwege teilen keine Straßen, Brücken bilden keine Kreuzung, Gehwege und Zufahrten werden ignoriert, lange Landstraßen werden gestückelt, die IDs sind kachelübergreifend stabil.
- **Straßennetz aus Vektorkacheln (neu, ersetzt Overpass):** Mit künstlich erzeugten Kacheln (Schnitt an Kachelrändern, weggekürzte Kreuzungspunkte) und mit **echten OpenFreeMap-Kacheln** aus Odenthal (Dorf) und Berlin-Prenzlauer Berg, verglichen mit den Overpass-Daten derselben Gegend vom selben Tag:
  - 62 von 62 bzw. 65 von 65 echten Kreuzungen gefunden, keine Straße fehlt.
  - Häuserblöcke: 21 statt 23 (Odenthal) und 73 statt 74 (Berlin).
  - Separat eingezeichnete Gehwege in Berlin: von 22,2 km bleiben 0,4 km als Weg übrig (vorher 19,8 km, vor der Korrektur).
  - Abschnitte laufen von Kreuzung zu Kreuzung, auch wo OSM eine Straße nur wegen eines Attributwechsels teilt.
  - Eine Zelle wird in ca. 0,15 s (PC) gebaut. Die Kennungen sind in Nachbarzellen identisch.
  - Mit der alten Version aufgedeckte Straßen werden wiedererkannt: 145 Abschnitte als bekannt markiert, beim erneuten Begehen 0 doppelt gezählt.
- **Lückenfüllung (neu):** Liegen zwei GPS-Punkte weit auseinander, wird der Weg entlang der Straßen ergänzt. Echte Berliner Route mit 9 Straßen: Bei einem Punkt alle 60 m bzw. 120 m werden alle 9 aufgedeckt, ohne Lückenfüllung nur 8 bzw. 7. Kein einziger Abschnitt zu viel. Bei zwei gleich kurzen Wegen um einen Block wird nichts ergänzt. Im Stillstand mit GPS-Streuung ergänzt sie nie etwas.
- Blockzuordnung: Ein Abschnitt deckt genau die 2 angrenzenden Blöcke auf, nicht den Block hinter der Parallelstraße und nicht die Kreuzungsfortsetzung. Riesenblöcke (24 ha) werden nur als Streifen aufgedeckt. Ein Fußweg im Park deckt nur einen Streifen auf. Bahnlinien begrenzen Blöcke. In Dörfern ohne Blöcke werden nur Häuser ohne dazwischenliegende Straße aufgedeckt.
- GPS-Jitter: 15 Zufallsläufe mit 0 Fehlaufdeckungen beim Stehen an einer Kreuzung (±7 m Streuung, 2,5 min), 0 Fehlaufdeckungen einer nur 20 m entfernten Parallelstraße und 0 verpasste Straßen. Ungenaue Positionen (±60 m) und GPS-Sprünge werden verworfen. Bei Autotempo zählen Fuß- und Radwege nicht.
- Offline-Erkundung: Fortschritt übersteht den Neustart ohne Duplikate.
- Ruhigere Positionsanzeige (neu): Im Stehen mit ±6 m GPS-Streuung liegt die angezeigte Position im Schnitt 3,3 m statt 7,2 m daneben. Ein ungenauer WLAN-Fix zieht die Figur nicht weg, und in Bewegung folgt sie sofort.
- Abzeichen (neu): Nur Vergaben mit der Signatur des Admin-Schlüssels zählen. „Alle bis Zeitpunkt T“ gilt nur für frühere Installationen. Spieler-ID und Gruppenmitglied werden erkannt. Zurücknehmen und erneutes Vergeben funktionieren. Die Ableitung des Admin-Schlüssels ist mit einem Testcode geprüft, der echte Code steht nicht im Repository.
- Gemerkte Orte fürs GPS (neu): Ein Ort gilt nach zweimal Stillstehen als bekannt, GPS-Streuung von 25 m zählt als derselbe Ort, 150 m daneben nicht. Höchstens 40 Orte, die ältesten fallen zuerst weg.
- Zusammenführung zweier Nutzer: Vereinigung ohne Verlust und ohne Duplikate, unabhängig von der Reihenfolge, Wiederholungen sind folgenlos.
- **Koop ohne Server (neu):** Die Signaturen (BIP-340) bestehen alle 19 offiziellen Testvektoren. Ereignisse werden exakt nach NIP-01 serialisiert, signiert und geprüft, eine veränderte Nachricht fällt durch. Nur wer den Gruppencode kennt, kann die Daten lesen: Mit einem anderen Code oder bei manipulierten Daten kommt nichts heraus. Auch über 1.000 Funde passen in Pakete unter 60 KB, und neue Funde ändern nur das letzte Paket.

**2. Koop-Server (11 Tests)**
Gruppencodes, idempotenter Upload, Zusammenführung zweier Spieler, Seitenweises Laden, 10 gleichzeitige Uploads ohne Duplikate, Abwehr ungültiger Eingaben, Ratenbegrenzung bei Code-Raten, Live-Position (Opt-in, Ablauf nach 10 min), HTTP-Ende-zu-Ende, Cloudflare-Worker-Code gegen D1-Semantik.
**Vertragstest:** Von der App-Logik erzeugte Elemente (256 im alten Format, 150 im neuen Kachel-Format) werden vom Server angenommen und unverändert zurückgegeben. Dieser Test hat früher einen echten Fehler gefunden, der behoben ist.
Zusätzlich lief der Worker lokal in der echten Cloudflare-Laufzeit (`wrangler dev`, workerd + lokale D1).

**3. Ende-zu-Ende im Android-Emulator (Android 14, auf Wunsch)**
Die APK wird installiert. Eine simulierte 1,1-km-GPS-Fahrt (6 m/s) führt über echte Helsinki-Straßen, im Hintergrund und mit ausgeschaltetem Bildschirm. Danach prüft der Test die Datenbank in der App, die Datenbank des Koop-Servers, Screenshots und Logs.
Letzter Lauf (23.10.2026, abends, mit automatischer GPS-Steuerung): Kacheln live von OpenFreeMap geladen, erste Zelle nach 24 s. Aufgedeckt wurden 17 Straßenabschnitte (1,04 km), 15 Häuserblöcke und 143 Gebäude. **176 von 176 Funden kamen auf dem Koop-Server an.** Während der Fahrt wurde das GPS nie gedrosselt. Erst nach der Ankunft, beim Stehen am Ziel, schaltete es auf „gedrosselt“. Die volle GPS-Pause (2,5 min) hat der Test nicht abgewartet.
Der Zellaufbau dauerte im Emulator 13 s (erstes Mal, ohne Cache) bzw. 1,6–6 s (aus dem Cache), am PC 0,15 s. Der CI-Emulator ist sehr langsam (Debug-Build, keine Grafikkarte).
Die Karte selbst lässt sich im GitHub-Emulator nicht anzeigen: Ohne Grafikkarte stürzt der OpenGL-Übersetzer des Emulators ab (`libGLESv2_enc.so`). Der Aufzeichnungsdienst startet danach von selbst neu und macht weiter, die Screenshots zeigen aber den Startbildschirm. Deshalb sind die neuen Kartenknöpfe nur in den Oberflächen-Bildern (4.) geprüft, nicht per Tipp im Emulator. Auf deinem Handy lief die Karte bisher.

**4. Oberfläche (Screenshots ohne Gerät)**
Menü (Info, Erkunden, Koop mit und ohne Gruppe, App) und die Kartenknöpfe (Menü, Kompass, 2D/3D, Standort, Start/Beenden) werden bei jedem Build mit Robolectric gerendert und als Bilder abgelegt. Geprüft am 23.10.2026: Texte, Schalter, Skizzen der Aufdeck-Modi, Kompassnadel, Info-Reiter mit GPS-Zustand.

**5. Aufdeck-Modi (Unit-Tests)**
Häuserblöcke, Straßenzug (einstellbare Breite, endet an der nächsten Straße) und Umkreis (Radius um den GPS-Weg, ohne Kartendaten, im Stillstand kaum neue Flächen). Eine Straße mit Block auf einer Seite und offenem Land auf der anderen zeigt beide Seiten. Sehr große Blöcke zeigen nur die Häuser an der Straße.

**6. Koop über echte öffentliche Relays (bei jedem Build)**
Der Build-Test spricht mit den echten Relays im Internet, nicht mit einer Attrappe:
- **Relay-Prüfung:** 12 öffentliche Relays wurden getestet, ob sie 25-KB- und 50-KB-Pakete annehmen und wieder herausgeben. 7 schafften beides. Die App nutzt fünf davon: nos.lol, relay.primal.net, nostr.mom, relay.snort.social und nostr-pub.wellorder.net. Aussortiert: Relays, die nur „Web-of-Trust“-Nutzer annehmen, nicht erreichbare und eines, das Daten zwar annimmt, aber nicht zurückgibt.
- **Zwei Mitglieder, echte Relays:** Anna erstellt eine Gruppe und veröffentlicht 540 Funde in 3 Paketen. Ben findet die Gruppe „CI-Gruppe“ nur über den Code und erhält alle 540. Anna erhält Bens 30 Funde. Beide sehen sich als Mitglieder, Bens eingeschalteter Live-Standort ist bei Anna sichtbar. Eine zweite Runde ohne neue Funde sendet nichts. Dauer: 11–12 s (zwei Läufe am 23.10.2026). Im zweiten Lauf antworteten bei der letzten Abfrage nur 3 der 5 Relays rechtzeitig (der Test stellt viele Abfragen in wenigen Sekunden). Die Daten kamen trotzdem vollständig an. Genau dafür schreibt die App an fünf Relays, und ein Paket gilt erst als verschickt, wenn mindestens zwei es gespeichert haben.

- **Live alle 2–3 Sekunden (neu):** Jedes der 5 Relays leitet die flüchtigen Live-Nachrichten weiter (3 von 3 angekommen), die erste nach 70–560 ms. Über alle Relays zusammen kamen 4 von 4 an, Funde reisen mit.

**7. Fenster und Figuren**
- Fensterflackern beim Drehen: im Browser mit echten Berliner Kacheln gemessen (Bild-zu-Bild-Unterschied beim Drehen in 0,35°-Schritten, Pixeldichte wie ein Handy). Altes Muster 9,0, neues Muster 6,7, also etwa ein Viertel weniger Flackern. Dazu überlappen Dach und Fassade nicht mehr (dort konnte es flimmern). Auf einem echten Handy nicht geprüft.
- Mini-Figuren und Abzeichen-Einblendung werden bei jedem Build gerendert (alle 72 Figurenbilder als Bogen, Abzeichen-Moment als Bild). Die Animation selbst (Drehen, Konfetti, Vibrieren) läuft nur auf dem Gerät und ist dort nicht geprüft.

**8. Aussehen der 3D-Karte (Vorschau)**
Die neuen 3D-Gebäude (Fassaden mit beleuchteten Fenstern, dunkle Dächer, mindestens ca. 8 m hoch) wurden mit denselben Berliner Kacheln, demselben Kartenstil und denselben Nebel- und Gebäudedaten in MapLibre GL JS (Browser) gerendert und begutachtet. Auf dem Handy rendert MapLibre Native. Das sollte gleich aussehen, ist aber nicht auf einem echten Gerät geprüft.

## ⚠️ Nicht getestet (konnte ich ohne echtes Handy nicht prüfen)

- **Echtes Gerät, echtes GPS draußen**, insbesondere in Häuserschluchten, unter Bäumen und im Auto.
- **Akkuverbrauch in Prozent pro Stunde**. Die automatische GPS-Steuerung (voll in Bewegung, gedrosselt nach 30 s Stehen, Pause nach 2,5 min bzw. 40 s an bekannten Orten) ist umgesetzt und mit Unit-Tests für das Merken der Orte geprüft, der Verbrauch aber nicht gemessen.
- **Kompass-Knopf, 2D/3D-Knopf, Standort-Knopf** und die 3D-Optik auf einem echten Gerät. Wie stark die Straßen unter dem Nebel auf deinem Bildschirm durchschimmern, habe ich nur in der Browser-Vorschau abgestimmt.
- **Ladezeit der ersten Kartenzelle auf einem echten Handy** (PC 0,15 s, langsamer CI-Emulator 2–14 s).
- **Selbst-Update auf einem echten Gerät** (Dialog, einmalige Android-Erlaubnis, Installation über die alte Version). Die Serverseite ist geprüft, der Ablauf auf dem Handy noch nicht.
- **Hersteller-Energiesparmodi** (Samsung, Xiaomi, Huawei …), die Hintergrunddienste beenden können.
- **Koop auf zwei echten Handys.** Getestet ist die Koop-Logik mit zwei simulierten Mitgliedern gegen die echten Relays (siehe 6). Der Emulator-Test oben lief noch gegen einen eigenen Testserver. Wie oft ein Handy-Netz (z. B. Firmen-WLAN) WebSocket-Verbindungen blockiert, ist nicht geprüft.
- **Wie lange die Relays Daten aufbewahren.** Öffentliche Relays geben keine Garantie. Deshalb schreibt die App an fünf gleichzeitig, und jedes Handy behält alle Funde (eigene und die der Gruppe) lokal. Fallen alle Relays aus oder löschen sie, gehen für neue Mitglieder ältere Funde verloren, nicht aber für bestehende.
- **Datenverbrauch des Koop-Modus** im Alltag. Rechnerisch: pro Sync meist ein Paket von ca. 13 KB an fünf Relays plus das Abholen neuer Pakete.
- **Eigener Koop-Server mit deinem Cloudflare-Konto** (optional). Workflow und Worker sind getestet, die Einrichtung mit deinem Konto nicht.
- **Live-Koop, Figuren und Abzeichen auf echten Handys** (Akku bei Live alle 2–3 s, wie flüssig die Figuren laufen, die Abzeichen-Einblendung).
- **Update-Benachrichtigung im Hintergrund:** Sie läuft über Android-WorkManager alle 3 Stunden. Ob und wann ein Hersteller das verzögert, ist nicht geprüft.
- **GPS-Genauigkeit draußen:** Der Fused-Anbieter und die sofortige Positionsabfrage sind eingebaut, aber nicht im Freien gemessen.
- Andere Android-Versionen als 14 (Mindestversion 8.0) und schwache Geräte.
- Die Darstellung der Hintergrundkarte (OpenFreeMap-Kacheln) im Emulator: Die Software-Grafik im CI ist sehr langsam.

## Bekannte Grenzen

- Koop über öffentliche Relays ist kostenlos, aber ohne Garantie (siehe oben). Die Relay-Betreiber sehen eure IP-Adressen und wann ihr synchronisiert, aber keine Inhalte. Wer das nicht möchte, kann einen eigenen Server einrichten (ANLEITUNG Abschnitt 4).
- Kartendaten kommen von OpenFreeMap (kostenloser Dienst ohne Garantie). Fällt er aus, läuft die Aufzeichnung weiter und wird nachträglich aufgedeckt. Bereits geladene Gebiete funktionieren offline.
- Vektorkacheln kennen keine OSM-Kennzeichnung für Gehwege. Gehwege werden an ihrer Lage erkannt (parallel 1–16 m neben einer Straße). Ein echter Fußweg direkt neben einer Straße wird dadurch ebenfalls ausgeblendet. Die Straße daneben deckt diese Fläche aber ohnehin auf.
- Gebäudehöhen stammen aus OpenStreetMap. Ohne Höhenangabe wird ein Haus mit ca. 8 m gezeigt.
- Die Qualität hängt von OpenStreetMap ab. Fehlende Straßen oder Gebäude in OSM fehlen auch in FoggyMap.
- Sehr lange Tunnel werden nicht aufgedeckt (kein GPS-Empfang). Brücken nur, wenn man darüber fährt.
- Die Prozentangabe bezieht sich auf die geladenen Kartenkacheln um deine Wege („deine Umgebung“), nicht auf eine ganze Stadt.
