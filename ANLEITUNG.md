# FoggyMap – Anleitung (nur mit dem Handy)

Alles hier geht im Browser bzw. der GitHub-App auf dem Android-Handy. Ein PC ist nicht nötig.

---

## 1. App installieren

1. Öffne auf dem Handy **github.com/JustFunLP/FoggyMap/releases** (ein GitHub-Konto ist nicht nötig, das Repository ist öffentlich).
2. Tippe bei **„FoggyMap – neueste Version“** unter *Assets* auf **FoggyMap.apk**.
3. Nach dem Download auf die Datei tippen. Android fragt beim ersten Mal, ob dein Browser Apps installieren darf: **„Zulassen“** → zurück → **Installieren**.
4. **Updates kommen danach von selbst:** Gibt es eine neue Version, meldet FoggyMap das innerhalb von ein paar Stunden per Benachrichtigung, auch wenn die App gerade nicht offen ist. Beim Öffnen prüft FoggyMap, ob es eine neuere Version gibt, und fragt *„Update verfügbar – Installieren?“*. Beim allerersten Update bittet Android einmalig um die Erlaubnis *„Apps aus dieser Quelle zulassen“* für FoggyMap. Die neue Version wird **über** die alte installiert, deine Erkundung bleibt erhalten (gleicher Signierschlüssel, die Datei wird vor der Installation per Prüfsumme kontrolliert). Von Hand: Menü → App → **„Prüfen“**.

> Google Play Protect kann warnen („Unbekannte App“). Das ist bei selbst gebauten Apps normal: *Weitere Details → Trotzdem installieren*.

## 2. Allein spielen

1. FoggyMap öffnen, die kurze Einführung durchblättern.
2. **„Erkundung starten“** antippen.
   - Standort: **„Während der Nutzung der App“** und **„Genau“** wählen.
   - Benachrichtigungen: **Zulassen** (zeigt, dass aufgezeichnet wird).
3. Losgehen, -radeln oder -fahren. Sobald du eine Straße betrittst, verschwindet der Nebel über dem ganzen Abschnitt bis zur nächsten Kreuzung und über den angrenzenden Häuserblöcken.
4. Das Handy darf in die Tasche, der Bildschirm darf aus sein, andere Apps dürfen offen sein. Beenden über **„Erkundung beenden“** unten auf der Karte oder in der Benachrichtigung. Wischst du FoggyMap aus der App-Übersicht weg, endet die Erkundung ebenfalls und das GPS geht aus.
5. **Knöpfe rechts unten auf der Karte:**
   - **Fortbewegung** (oberster Knopf): zeigt, wie du gerade unterwegs bist, als Läufer, Rad, Auto oder Zug. Ein kleines „A“ heißt: automatisch erkannt. Tippen, um es selbst festzulegen (gilt bis zum Ende der Erkundung). Beim **Zug** wird ein Streifen entlang der Gleise aufgedeckt (ca. 40 m zu jeder Seite, auch rückwirkend für die ersten Minuten, bis der Zug erkannt war), Straßen zählen dabei nicht. Beim **Auto** zählen nur Straßen, auf denen Autos fahren. Erkannt wird der Zug, wenn du schnell genau auf einer Bahnstrecke unterwegs bist, abseits von Straßen.
   - **Kompass:** Die Nadel zeigt immer nach Norden. Ein Tipp dreht die Karte nach Norden und hält sie so, auch wenn sie auf dich fixiert ist (der Knopf leuchtet dann). Noch ein Tipp: wieder deine Blickrichtung oben.
   - **2D / 3D:** wechselt zwischen flacher Karte und geneigter 3D-Ansicht.
   - **Standort:** fixiert die Karte auf dich, deine Blickrichtung oben (beim Rad- und Autofahren die Fahrtrichtung). Verschiebst du die Karte mit dem Finger, löst sich das. Zoomen geht, ohne dass es sich löst.
6. **Menü** (oben rechts) mit vier Reitern:
   - **Info:** wie viel du erkundet hast, diese Tour, was das GPS gerade macht, alle Statistiken.
   - **Erkunden:** Was wird aufgedeckt? *Häuserblöcke* (Stadt), *Straßenzug* (Streifen links und rechts, Breite einstellbar, ideal im Dorf) oder *Umkreis* (Radius um deinen Weg, sofort, auch ohne Kartendaten). Dazu 3D-Gebäude, Animationen und Offline-Karte.
   - **Koop:** Gruppe erstellen oder beitreten, Mitglieder, Live-Standort.
   - **App:** Updates, Berechtigungen, Daten löschen.

**Tipps**
- Dein Weg erscheint sofort als heller Lichtstreifen. Die Straßendaten kommen nach ein, zwei Sekunden, dann löst sich der Nebel über der Straße auf.
- Unter dem Nebel schimmern die Straßen schwach durch, damit du siehst, wo es noch etwas zu entdecken gibt.
- Beim Erkunden lädt die App die Umgebung im Hintergrund (im WLAN die ganze Stadt, ca. 8 km). Wer vorab alles will: Menü → Erkunden → **Ganze Stadt herunterladen**. Danach geht alles ohne Netz.
- Ohne Internet läuft die Aufzeichnung weiter. Die Wege werden gespeichert und aufgedeckt, sobald die Kartendaten geladen werden können.
- Samsung/Xiaomi/Huawei: Falls die Aufzeichnung im Hintergrund stoppt, Menü → App → *Akku-Optimierung* auf „Nicht eingeschränkt“ stellen.
- Akku: Es gibt keine GPS-Einstellungen. Wie bei Google Maps und Waze kommt dein Standort vom Google-Standortdienst. In Bewegung misst FoggyMap alle 2–3 Sekunden. Beim ersten Start fragt die App einmal nach **„Körperliche Aktivität“**: Erlaubst du das, merkt dein Handy selbst, wann du stehst (meist nach einer halben bis ganzen Minute). Zeigt auch das GPS danach 20 Sekunden keine Bewegung, geht es ganz aus und springt an, sobald du losgehst (nachträglich: Menü → Info → Bewegungserkennung → *Erlauben*). Ohne diese Erlaubnis wird nach 30 Sekunden gedrosselt und nach 2,5 Minuten ausgeschaltet (an Orten, an denen du oft stehst, nach 40 Sekunden). Alles bleibt auf deinem Handy.
- Deine Figur steht auf der Straße, auf der du gehst oder fährst, und gleitet zwischen den GPS-Positionen die Straße entlang, statt hin und her zu springen. Auf Plätzen und in Parks steht sie dort, wo das GPS dich sieht.
- Nebel-Wolken und Fenster behalten beim Zoomen ihre Größe auf dem Boden bzw. an der Fassade.

---

## 3. Mit Freunden und Kollegen spielen (Koop)

Es muss **nichts eingerichtet** werden: kein Server, kein Konto, kein GitHub. Alles passiert in der App.

1. **Du:** FoggyMap → oben rechts **Menü** → Reiter **Koop** → Anzeigename und Gruppenname eintragen → **Gruppe erstellen**.
2. Es erscheint der Gruppencode. Tippe auf das **Teilen-Symbol** daneben und schicke die Einladung z. B. per WhatsApp. In der Nachricht stehen der Download-Link der App und der Code.
3. **Freund/Kollege:** installiert die App über den Link in der Nachricht (wie in Abschnitt 1, ohne GitHub-Konto) → App öffnen → **Menü → Koop** → die Einladungsnachricht in das Feld *„Einladung einfügen“* kopieren (oder in WhatsApp lange auf die Nachricht tippen → **Teilen → FoggyMap**) → **Beitreten**.
4. Ab jetzt sehen alle, was jeder aufdeckt. Synchronisiert wird automatisch alle 2–3 Minuten während der Erkundung, beim Öffnen der App und sobald wieder Internet da ist. Von Hand: **Menü → Koop** → das Sync-Symbol rechts neben dem Status.

**Auf der Karte** bist du eine kleine violette Figur, deine Freunde sind türkis, mit Namen darunter. Je nach Fortbewegung ist es ein Läufer, ein Radfahrer, ein Auto oder ein Zug. Wer unterwegs ist, läuft auf der Stelle. Der Lichtkegel am Boden zeigt die Blickrichtung. Mit eingeschaltetem Live-Standort bewegen sich die Figuren alle 2–3 Sekunden, solange jemand die Karte offen hat, und ihr seht den Nebel beim anderen fast sofort verschwinden. Schaut gerade niemand zu, sendet das Handy nur alle 20 Sekunden (spart Akku).

**Wie funktioniert das ohne Server?** Die Funde laufen über mehrere öffentliche **Nostr-Relays** (frei nutzbare Nachrichten-Server, die jeder verwenden darf). Alles ist mit eurem Gruppencode **verschlüsselt** (AES-256). Die Relays sehen nur unlesbare Pakete, keine Namen, keine Orte. Die App schreibt gleichzeitig an mehrere Relays (Funde an fünf, Live an drei), damit nichts verloren geht, wenn eines ausfällt. Deine eigenen Funde bleiben ohnehin immer auf deinem Handy.

**Was wird geteilt?** Nur aufgedeckte Flächen, Kilometerzahlen und der Anzeigename. Der GPS-Verlauf wird nie übertragen. Den Live-Standort sehen andere nur, wenn du ihn in *Menü → Koop → Live-Standort* ausdrücklich einschaltest. Dann sehen sie dich während der Erkundung alle 2–3 Sekunden (solange jemand die Karte offen hat, sonst alle 20 Sekunden), inklusive dem, was du gerade aufdeckst. Nach dem Ausschalten oder Beenden verschwindet deine Position.
Aus den aufgedeckten Flächen ist erkennbar, wo du unterwegs warst. Gib den Gruppencode deshalb nur Leuten, die das sehen dürfen. Wer den Code kennt, kann beitreten und alles sehen.

**Abzeichen:** Bekommst du ein neues Abzeichen (z. B. „Alpha Tester“), erscheint es beim nächsten App-Start mit einer großen Einblendung. Deine Abzeichen und deine Spieler-ID stehen unter Menü → Info.

*Für den Admin:* Menü → App → ganz unten **„Admin“** → Admin-Code eingeben. Danach kannst du dort Abzeichen verleihen:
- **an alle, die die App bis jetzt installiert haben** (z. B. Alpha Tester),
- **per Spieler-ID** (die ID steht beim Empfänger unter Menü → Info),
- **an ein Mitglied deiner Gruppe**.

Du kannst Abzeichen auch wieder zurücknehmen oder neue anlegen (Name, Beschreibung, Symbol, Farbe). Der Admin-Code steht nicht in der App: Wer ihn nicht kennt, kann keine Abzeichen vergeben.

---

## 4. Eigener Koop-Server (optional, nur für Technikaffine)

Nicht nötig. Wer die Daten lieber auf einem eigenen Server hat statt auf öffentlichen Relays, kann einen einrichten und die Adresse unter **Menü → App → Koop-Server** im Feld *Eigene Server-Adresse* eintragen. Wer per Einladung beitritt, übernimmt die Adresse automatisch.


Empfohlen ist **Cloudflare Workers + D1**: **kostenlos**, mit HTTPS, die Daten bleiben dauerhaft erhalten. Das Free-Kontingent (100.000 Anfragen/Tag) reicht für eine Gruppe von Kollegen um ein Vielfaches.

#### Weg A: automatisch per GitHub (empfohlen)

1. **Cloudflare-Konto:** auf **dash.cloudflare.com/sign-up** kostenlos registrieren und die E-Mail bestätigen.
2. Im Cloudflare-Dashboard links (Menü ☰) **„Workers & Pages“** öffnen. Falls nach einer *workers.dev-Subdomain* gefragt wird, einen Namen wählen und bestätigen. Das musst du nur einmal machen.
3. **Account-ID kopieren:** Auf der Seite „Workers & Pages“ steht rechts (am Handy weiter unten) **„Account ID“** → kopieren und z. B. in einer Notiz zwischenspeichern.
4. **API-Token erstellen:** Oben rechts auf das Profil-Symbol → **My Profile** → **API Tokens** → **Create Token** → bei **„Edit Cloudflare Workers“** auf **Use template** tippen.
   - Unter *Permissions* auf **+ Add more** tippen und **Account → D1 → Edit** auswählen.
   - Unter *Account Resources* dein Konto auswählen.
   - **Continue to summary → Create Token** → Token **kopieren**. Er wird nur einmal angezeigt.
5. **In GitHub hinterlegen:** github.com/JustFunLP/FoggyMap → **Settings** → **Secrets and variables** → **Actions** → **New repository secret**:
   - Name `CLOUDFLARE_API_TOKEN`, Wert = der Token
   - Name `CLOUDFLARE_ACCOUNT_ID`, Wert = die Account-ID
6. **Server starten:** Im Repo **Actions** → links **„Koop-Server (Cloudflare) einrichten“** → **Run workflow** → **Run workflow**.
   Nach ca. 2 Minuten steht in der Zusammenfassung des Laufs **„✅ Koop-Server läuft“** mit der Adresse (`https://foggymap-sync.<name>.workers.dev`).
7. Danach wird **automatisch eine neue FoggyMap.apk** mit eingebauter Serveradresse gebaut (Actions → „FoggyMap APK“, ca. 8 Minuten). Diese Version installierst du wie unter Punkt 1, und deine Kollegen bekommen dieselbe Datei.

#### Weg B: ohne GitHub-Secrets, direkt im Cloudflare-Dashboard

1. Cloudflare-Konto wie oben.
2. **D1-Datenbank:** Menü → *Storage & Databases* → **D1 SQL Database** → **Create** → Name `foggymap` → Create.
3. **Worker:** Menü → **Workers & Pages** → **Create** → **Start with Hello World** → Name `foggymap-sync` → **Deploy**.
4. **Code einfügen:** **Edit code** → den gesamten Inhalt löschen → den Inhalt der Datei
   `server/cloudflare/dashboard-worker.js` aus diesem Repository einfügen. Am Handy geht das so: Datei auf GitHub öffnen → **Raw** → alles markieren → kopieren. Dann **Deploy**.
5. **Datenbank verbinden:** Worker → **Settings** → **Bindings** → **Add** → **D1 database** → Variable name **`DB`** → Datenbank `foggymap` → **Save/Deploy**.
6. Die Adresse `https://foggymap-sync.<name>.workers.dev` öffnen. Es muss `{"ok":true,...}` erscheinen.
7. In der App: **Menü → App → Koop-Server** → Feld *Eigene Server-Adresse* → diese Adresse eintragen (nur der Gruppenersteller; wer per Einladung beitritt, bekommt sie automatisch).

#### Weg C: eigener Server (für Technikaffine)
`server/docker-compose.yml` startet den Node.js-Server mit SQLite und automatischem HTTPS über Caddy:
`DOMAIN=foggy.example.de docker compose up -d` (Domain muss auf den Server zeigen).

---

## 5. Wenn etwas nicht klappt

**Problem melden:** Menü → App → **„Problem melden“** → kurz beschreiben, was passiert ist → **„Bericht teilen“** (z. B. per WhatsApp an dich selbst und dann hier in den Chat hochladen). Der Bericht enthält Gerät, Einstellungen, GPS-Qualität, Akkuverbrauch, Abstürze und das Protokoll der App. Er enthält **keine Orte, keine Straßennamen, keine Namen und keinen Gruppencode**. Mit „Bericht ansehen“ siehst du vorher alles.
Stürzt FoggyMap ab oder friert ein, fragt die App beim nächsten Start, ob du den Bericht teilen möchtest.

**Akkuverbrauch:** Menü → Info → *Akku & GPS* zeigt, wie viel Prozent pro Stunde das Handy beim Erkunden verliert (diese Tour, Durchschnitt, mit Live-Standort).

| Problem | Lösung |
|---|---|
| „Kartendaten: …“ / „Offline …“ | Kein Netz oder der Kartenserver ist kurz nicht erreichbar. Die App versucht es nach wenigen Sekunden erneut, deine Wege gehen nicht verloren. |
| Nichts wird aufgedeckt | GPS-Genauigkeit oben in der Statuszeile prüfen. Über ±35 m (z. B. drinnen) wird absichtlich nichts aufgedeckt. |
| Aufzeichnung stoppt im Hintergrund | Menü → App → Akku-Optimierung → „Nicht eingeschränkt“. Bei Xiaomi zusätzlich „Autostart“ erlauben. |
| Koop: „Keine Verbindung zu den Koop-Relays“ | Kein Internet, oder ein Firmen-/Hotel-WLAN blockiert WebSockets. Mobile Daten versuchen. Deine Funde bleiben gespeichert und werden später geteilt. |
| Koop: „Keine Gruppe mit diesem Code gefunden“ | Code prüfen. Der Gruppenersteller muss die App nach dem Erstellen einmal mit Internet geöffnet haben. |
| Eigener Server: „Server nicht erreichbar“ | Server-Adresse prüfen (muss mit `https://` beginnen). Beim Cloudflare-Weg B die D1-Bindung `DB` prüfen. |
| Workflow „Koop-Server“ schlägt fehl (nur eigener Server) | In der Zusammenfassung steht der Grund. Meist fehlen Secrets oder die workers.dev-Subdomain (Schritt 2). |
