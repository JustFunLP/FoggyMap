# FoggyMap – Anleitung (nur mit dem Handy)

Alles hier geht im Browser bzw. der GitHub-App auf dem Android-Handy. Ein PC ist nicht nötig.

---

## 1. App installieren

1. Öffne auf dem Handy **github.com/JustFunLP/FoggyMap/releases** (eingeloggt in dein GitHub-Konto).
2. Tippe bei **„FoggyMap – neueste Version“** unter *Assets* auf **FoggyMap.apk**.
3. Nach dem Download auf die Datei tippen. Android fragt beim ersten Mal, ob dein Browser Apps installieren darf: **„Zulassen“** → zurück → **Installieren**.
4. Spätere Versionen installierst du genauso. Sie werden **über** die alte Version installiert, deine Erkundung bleibt erhalten (gleicher Signierschlüssel).

> Google Play Protect kann warnen („Unbekannte App“). Das ist bei selbst gebauten Apps normal: *Weitere Details → Trotzdem installieren*.

## 2. Allein spielen

1. FoggyMap öffnen, die kurze Einführung durchblättern.
2. **„Erkundung starten“** antippen.
   - Standort: **„Während der Nutzung der App“** und **„Genau“** wählen.
   - Benachrichtigungen: **Zulassen** (zeigt, dass aufgezeichnet wird).
3. Losgehen, -radeln oder -fahren. Sobald du eine Straße betrittst, verschwindet der Nebel über dem ganzen Abschnitt bis zur nächsten Kreuzung und über den angrenzenden Häuserblöcken.
4. Das Handy darf in die Tasche, der Bildschirm darf aus sein. Beenden über **„Erkundung beenden“** oder in der Benachrichtigung.

**Tipps**
- Beim ersten Start in einer neuen Gegend lädt die App die Straßen (ca. 1 km² pro Kachel). Danach geht das auch offline.
- Ohne Internet läuft die Aufzeichnung weiter. Die Wege werden gespeichert und aufgedeckt, sobald die Kartendaten geladen werden können.
- Samsung/Xiaomi/Huawei: Falls die Aufzeichnung im Hintergrund stoppt, in FoggyMap → ⚙ → *Akku-Optimierung* die App auf „Nicht eingeschränkt“ stellen.
- Akku: ⚙ → *Akku & GPS* → „Ausgewogen“ (Standard) oder „Sparsam“.

---

## 3. Koop-Server einrichten (einmalig, ca. 10 Minuten)

Für das gemeinsame Spielen braucht ihr einen kleinen Server. Empfohlen ist **Cloudflare Workers + D1**: **kostenlos**, mit HTTPS, die Daten bleiben dauerhaft erhalten. Das Free-Kontingent (100.000 Anfragen/Tag) reicht für eine Gruppe von Kollegen um ein Vielfaches.

### Weg A: automatisch per GitHub (empfohlen)

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

### Weg B: ohne GitHub-Secrets, direkt im Cloudflare-Dashboard

1. Cloudflare-Konto wie oben.
2. **D1-Datenbank:** Menü → *Storage & Databases* → **D1 SQL Database** → **Create** → Name `foggymap` → Create.
3. **Worker:** Menü → **Workers & Pages** → **Create** → **Start with Hello World** → Name `foggymap-sync` → **Deploy**.
4. **Code einfügen:** **Edit code** → den gesamten Inhalt löschen → den Inhalt der Datei
   `server/cloudflare/dashboard-worker.js` aus diesem Repository einfügen. Am Handy geht das so: Datei auf GitHub öffnen → **Raw** → alles markieren → kopieren. Dann **Deploy**.
5. **Datenbank verbinden:** Worker → **Settings** → **Bindings** → **Add** → **D1 database** → Variable name **`DB`** → Datenbank `foggymap` → **Save/Deploy**.
6. Die Adresse `https://foggymap-sync.<name>.workers.dev` öffnen. Es muss `{"ok":true,...}` erscheinen.
7. In der App: **Koop** → Feld **Server-Adresse** → diese Adresse eintragen (nur der Gruppenersteller; wer per Einladung beitritt, bekommt sie automatisch).

### Weg C: eigener Server (für Technikaffine)
`server/docker-compose.yml` startet den Node.js-Server mit SQLite und automatischem HTTPS über Caddy:
`DOMAIN=foggy.example.de docker compose up -d` (Domain muss auf den Server zeigen).

---

## 4. Mit Kollegen spielen

1. **Du:** FoggyMap → oben **Koop** → Anzeigename und Gruppenname eintragen → **Gruppe erstellen**.
2. Auf das **Teilen-Symbol** neben dem Code tippen und die Einladung z. B. per WhatsApp verschicken.
3. **Die APK an Kollegen geben:** Dein Repository ist privat. Kollegen können den GitHub-Link deshalb nicht öffnen. Schicke ihnen die Datei **FoggyMap.apk** direkt, z. B. per WhatsApp als *Dokument*, über Google Drive oder Quick Share.
4. **Kollege:** APK installieren → App öffnen → **Koop** → die komplette Einladungsnachricht in das Feld *„Einladung einfügen“* kopieren (oder in WhatsApp lange auf die Nachricht tippen → **Teilen → FoggyMap**) → **Beitreten**.
5. Ab jetzt sehen alle, was jeder aufdeckt. Synchronisiert wird automatisch alle paar Minuten, nach neuen Funden und sobald wieder Internet da ist.

**Was wird geteilt?** Nur aufgedeckte Flächen, Kilometerzahlen und der Anzeigename. Der GPS-Verlauf wird nie übertragen. Den Live-Standort sehen andere nur, wenn du ihn in *Koop → Live-Standort* ausdrücklich einschaltest. Er verfällt nach 10 Minuten.

---

## 5. Wenn etwas nicht klappt

| Problem | Lösung |
|---|---|
| „Kartendaten: … 429/504“ | Der öffentliche OpenStreetMap-Server (Overpass) ist ausgelastet. Die App versucht es automatisch erneut, deine Wege gehen nicht verloren. |
| Nichts wird aufgedeckt | GPS-Genauigkeit oben in der Statuszeile prüfen. Über ±35 m (z. B. drinnen) wird absichtlich nichts aufgedeckt. |
| Aufzeichnung stoppt im Hintergrund | ⚙ → Akku-Optimierung → „Nicht eingeschränkt“. Bei Xiaomi zusätzlich „Autostart“ erlauben. |
| Koop: „Server nicht erreichbar“ | Server-Adresse prüfen (muss mit `https://` beginnen). Beim Cloudflare-Weg B die D1-Bindung `DB` prüfen. |
| Workflow „Koop-Server“ schlägt fehl | In der Zusammenfassung steht der Grund. Meist fehlen Secrets oder die workers.dev-Subdomain (Schritt 2). |
