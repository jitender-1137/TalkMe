// Free, self-hosted NSFW image classifier for TalkMe.
//
// The Java backend POSTs an image (path on a shared volume, or raw bytes) and gets
// back a verdict. Runs nsfwjs on the pure-JS tfjs CPU backend — no paid APIs, no
// GPU and no native libtensorflow, so it works on arm64 (Apple Silicon) and x86 alike.
// Images are decoded to raw RGB pixels with sharp (jpeg/png/webp/gif), which ships
// reliable prebuilt binaries for both architectures.
//
//   POST /classify   body: { "path": "/opt/media/talkMe/uuid.jpg" }   (shared volume)
//          or         multipart/form-data field "file"
//   200 -> { "nsfw": true, "scores": { "Porn":0.9, "Sexy":0.1, "Hentai":0.0, "Drawing":0.0, "Neutral":0.0 } }
//
// NSFW = Porn + Hentai + 0.5*Sexy >= NSFW_THRESHOLD (default 0.7), tunable via env.

const fs = require("fs");
const express = require("express");
const tf = require("@tensorflow/tfjs");
const sharp = require("sharp");
const nsfw = require("nsfwjs");

const PORT = process.env.PORT || 8081;
const THRESHOLD = parseFloat(process.env.NSFW_THRESHOLD || "0.7");

const app = express();
app.use(express.json({ limit: "1mb" }));
// Raw body for multipart-less binary posts (optional).
app.use(express.raw({ type: "application/octet-stream", limit: "20mb" }));

let model = null;
async function getModel() {
  if (!model) {
    await tf.setBackend("cpu");
    await tf.ready();
    model = await nsfw.load(); // MobileNetV2 (bundled)
  }
  return model;
}

// Decode any common image format to an int32 RGB tensor [height, width, 3].
// Replaces tf.node.decodeImage (only available in the native tfjs-node build).
async function decodeImage(buf) {
  const { data, info } = await sharp(buf)
    .removeAlpha() // drop alpha so we always get 3 channels
    .raw()
    .toBuffer({ resolveWithObject: true });
  return tf.tensor3d(new Int32Array(data), [info.height, info.width, info.channels], "int32");
}

function isNsfw(scores) {
  const s = (k) => scores[k] || 0;
  return s("Porn") + s("Hentai") + 0.5 * s("Sexy") >= THRESHOLD;
}

async function classifyBuffer(buf) {
  const m = await getModel();
  const image = await decodeImage(buf);
  try {
    const preds = await m.classify(image);
    const scores = {};
    for (const p of preds) scores[p.className] = p.probability;
    return { nsfw: isNsfw(scores), scores };
  } finally {
    image.dispose();
  }
}

app.get("/health", (_req, res) => res.json({ ok: true, threshold: THRESHOLD }));

app.post("/classify", async (req, res) => {
  try {
    let buf = null;
    if (req.is("application/json") && req.body && req.body.path) {
      if (!fs.existsSync(req.body.path)) return res.status(404).json({ error: "file not found" });
      buf = fs.readFileSync(req.body.path);
    } else if (Buffer.isBuffer(req.body) && req.body.length > 0) {
      buf = req.body;
    }
    if (!buf) return res.status(400).json({ error: "no image provided" });

    const result = await classifyBuffer(buf);
    return res.json(result);
  } catch (e) {
    console.error("[nsfw] classify error:", e.message);
    return res.status(422).json({ error: "could not classify image" });
  }
});

getModel()
  .then(() => app.listen(PORT, () => console.log(`[nsfw] listening on :${PORT} (threshold ${THRESHOLD})`)))
  .catch((e) => {
    console.error("[nsfw] failed to load model:", e);
    process.exit(1);
  });
