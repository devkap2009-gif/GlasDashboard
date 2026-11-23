# Glas-Dashboard für Android

Ein transparentes Panel aus Milchglas, das über allen Apps liegt. Ein Hintergrunddienst
hält oben am Bildschirm eine schmale Wischzone bereit: Zwei Finger direkt unter der
Statusleiste ansetzen und nach unten ziehen, dann fährt das Dashboard mit den Fingern herein.

**Inhalt:** Uhr und Datum, Akku, Schalter (WLAN, Bluetooth, Nicht stören, Taschenlampe),
Timer, Aufgabenliste, Notiz. Ab Android 12 wird der Hintergrund echt unscharf.

**Schließen:** zwei Finger nach oben, daneben tippen oder Zurück.

**Weitere Wege zum Öffnen:** Kachel „Dashboard“ in den Schnelleinstellungen
(zwei Finger von ganz oben öffnen die Schnelleinstellungen direkt) oder ein Tipp auf die Benachrichtigung.

## APK bauen

### A) Ohne PC, über GitHub (empfohlen)
1. Lade den ganzen Ordner in ein neues GitHub-Repository hoch.
2. Der Workflow `.github/workflows/build.yml` baut die App automatisch.
3. Unter **Actions → letzter Lauf → Artifacts** lädst du `GlasDashboard-APK` herunter (eine ZIP mit `app-debug.apk`).
4. Auf dem Handy entpacken, die APK antippen und „Installation aus dieser Quelle zulassen“ erlauben.

### B) Mit PC
Den Ordner in Android Studio öffnen und auf ▶ tippen, oder `./gradlew assembleDebug` ausführen.
Die APK liegt danach unter `app/build/outputs/apk/debug/`.

## Einrichten in der App
1. „Über anderen Apps einblenden“ erlauben
2. Benachrichtigungen erlauben
3. Akku-Optimierung ausschalten (sonst beendet Android den Dienst evtl.)
4. „Dienst starten“

Unter Xiaomi/MIUI, Huawei & Co. zusätzlich „Autostart“ und „Im Hintergrund anzeigen“ erlauben.

## Bekannte Grenze
Taps genau in der schmalen Wischzone landen nicht in der App darunter. Die Höhe ist in der App
einstellbar (16–72 dp). Den Systemgesten-Bereich ganz oben (Statusleiste) darf keine App abfangen,
deshalb sitzt die Zone direkt darunter.
