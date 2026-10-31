# CabScreen

Ads on tablets inside cabs. Videos play only while a ride is in progress.

| Part | Folder | What it does |
|---|---|---|
| Driver phone app (RideScan) | `app/` | Reads ride-app notifications (Ola, Uber, Rapido, WhatsApp test) and sends them to the server |
| Cab tablet app (CabScreen) | `tablet/` | Full-screen player: downloads ad videos, loops them during a ride, stops when it ends |
| Cloud API | `functions/` | Firebase Cloud Function: `POST /api/event`, `GET /api/state` (secret fleet key) |
| Website | `web/` | Firebase Hosting: upload/delete videos, see rides, tablets, phones, notifications (Google sign-in, admin only) |
| Local server (offline/LAN testing) | `desktop/` | Same thing on one Wi-Fi: `python desktop/server.py`, open http://localhost:8000 |

## Setup

1. Secret key — pick a long random string and put it in both places:
   - `local.properties` (Android): `fleet.key=...` (plus `sdk.dir=...`)
   - `functions/.env`: `FLEET_KEY=...` (see `functions/.env.example`)
2. Admin emails are in `firestore.rules` and `storage.rules`.
3. Deploy (Firebase Blaze plan needed for Functions + Storage):
   `cd functions && npm install && cd .. && firebase deploy`
4. Build APKs (JDK 17 + Android SDK): `./gradlew assembleDebug`
   - `app/build/outputs/apk/debug/app-debug.apk` → driver phone (grant notification access)
   - `tablet/build/outputs/apk/debug/tablet-debug.apk` → cab tablet

Ride start/end phrases live in `app/src/main/java/com/ridescan/RideParser.java` and `functions/index.js`.
Self-check: `java app/src/main/java/com/ridescan/RideParser.java`
