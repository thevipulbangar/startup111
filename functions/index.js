// CabScreen cloud API (Firebase Cloud Function, served at https://<project>.web.app/api/...)
//   POST /api/event?key=K   driver phone (RideScan) sends each notification; START/END flips the ride
//   GET  /api/state?key=K   cab tablet (CabScreen) asks: is a ride on, which videos to play
// The website talks to Firestore/Storage directly (Google sign-in, admin only, see firestore.rules).
const { onRequest } = require("firebase-functions/v2/https");
const { initializeApp } = require("firebase-admin/app");
const { getFirestore } = require("firebase-admin/firestore");

initializeApp();
const db = getFirestore();
const KEY = process.env.FLEET_KEY; // from functions/.env; the same key is built into both apps

// Keep in step with RideParser.java on the phone (a phone label of START/END is trusted as-is).
const END_WORDS = ["ride completed", "trip completed", "ride ended", "trip ended", "ride is over", "ride over",
  "trip is over", "ride has ended", "trip has ended", "ride has been ended", "ride is ended",
  "ride has been completed", "trip has been completed", "collect cash", "collect payment",
  "drop completed", "you have reached the drop"];
const START_WORDS = ["ride started", "trip started", "ride has started", "ride is started", "ride has been started",
  "trip has started", "started the ride", "started the trip", "ride is on", "otp verified",
  "heading to drop", "on the way to drop"];
const WHATSAPP_TEST_SENDER = "chituuuuuu"; // test mode: only this WhatsApp contact can start/stop a ride

function classify(e) {
  if (e.app === "WhatsApp" && !e.title.toLowerCase().includes(WHATSAPP_TEST_SENDER)) return "OTHER";
  if (e.type === "START" || e.type === "END") return e.type;
  const t = `${e.title} ${e.text}`.toLowerCase();
  if (END_WORDS.some(w => t.includes(w))) return "END";
  if (START_WORDS.some(w => t.includes(w))) return "START";
  return "OTHER";
}

const str = (v, max) => String(v ?? "").slice(0, max);

// ponytail: every tablet polls every 2s, so ride + video list are cached per instance for 2s
// (Firestore reads stay flat no matter how many tablets). Switch tablets to Firestore listeners at fleet scale.
let cache = { at: 0, body: null };
async function stateBody() {
  if (Date.now() - cache.at < 2000 && cache.body) return cache.body;
  const [ride, vids] = await Promise.all([
    db.doc("rides/current").get(),
    db.collection("videos").orderBy("created").get(),
  ]);
  const body = {
    ride: ride.exists ? ride.data() : { active: false, since: 0, source: "" },
    videos: vids.docs.map(d => {
      const v = d.data();
      return { name: v.path.split("/").pop(), size: v.size, url: v.url };
    }),
  };
  cache = { at: Date.now(), body };
  return body;
}

// Heartbeats: write a tablet doc only when what it plays changes, or every 30s (saves Firestore writes).
const lastBeat = new Map();

exports.api = onRequest({ region: "asia-south1", maxInstances: 10 }, async (req, res) => {
  res.set("Cache-Control", "no-store");
  if (!KEY || req.query.key !== KEY) return res.status(403).send("Bad key");
  const ip = String(req.headers["x-forwarded-for"] || req.ip || "").split(",")[0].trim();

  if (req.method === "POST" && req.path.endsWith("/event")) {
    const b = req.body;
    if (!b || typeof b !== "object" || Array.isArray(b)) return res.status(400).send("Bad event");
    const e = {
      time: Number(b.time) || Date.now(), received: Date.now(), app: str(b.app, 40), type: str(b.type, 10),
      fare: str(b.fare, 20), title: str(b.title, 300), text: str(b.text, 3000),
      device: str(b.device, 80), android: str(b.android, 20), ip,
    };
    e.type = classify(e);
    await db.collection("events").add(e);
    if (e.type === "START" || e.type === "END") {
      await db.doc("rides/current").set({ active: e.type === "START", since: Date.now(), source: `${e.app} on ${e.device || ip}` });
      cache.at = 0;
    }
    return res.status(204).end();
  }

  if (req.method === "GET" && req.path.endsWith("/state")) {
    const id = str(req.query.id || req.query.tablet, 64).replace(/[^\w.-]/g, "_");
    if (id) {
      const playing = str(req.query.playing, 200);
      const prev = lastBeat.get(id);
      if (!prev || prev.playing !== playing || Date.now() - prev.at > 30000) {
        lastBeat.set(id, { playing, at: Date.now() });
        await db.doc(`tablets/${id}`).set({ name: str(req.query.tablet, 80), ip, playing, seen: Date.now() });
      }
    }
    return res.json(await stateBody());
  }

  res.status(404).send("Not found");
});
