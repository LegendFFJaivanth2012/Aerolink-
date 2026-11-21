import express from "express";
import multer from "multer";
import fs from "fs";
import path from "path";
import crypto from "crypto";

const app = express();
const PORT = process.env.PORT || 8080;
const DATA = process.env.AEROLINK_DATA || path.join(process.cwd(), "data");
fs.mkdirSync(DATA, { recursive: true });

const ready = new Map();
const transfers = new Map();
const upload = multer({
  dest: DATA,
  limits: { fileSize: 2 * 1024 * 1024 * 1024 }
});

app.use(express.json({ limit: "64kb" }));

app.get("/health", (_, res) => res.json({ ok: true, service: "aerolink-relay", version: "0.31" }));

app.post("/v1/ready/:recipientId", (req, res) => {
  const id = String(req.params.recipientId || "").toUpperCase();
  if (!id) return res.status(400).json({ error: "recipientId required" });
  ready.set(id, Date.now() + 10 * 60 * 1000);
  res.json({ ok: true, expiresInSeconds: 600 });
});

app.get("/v1/inbox/:recipientId", (req, res) => {
  const recipientId = String(req.params.recipientId || "").toUpperCase();
  const expiry = ready.get(recipientId) || 0;
  if (expiry < Date.now()) {
    ready.delete(recipientId);
    return res.json({ available: false });
  }

  for (const [id, t] of transfers) {
    if (t.recipientId === recipientId && !t.claimed) {
      t.claimed = true;
      return res.json({
        available: true,
        transferId: id,
        name: t.name,
        size: t.size
      });
    }
  }
  res.json({ available: false });
});

app.post("/v1/transfers", upload.single("file"), (req, res) => {
  const senderId = String(req.header("X-AeroLink-Sender") || "").toUpperCase();
  const recipientId = String(req.header("X-AeroLink-Recipient") || "").toUpperCase();

  if (!senderId || !recipientId) {
    if (req.file) fs.rmSync(req.file.path, { force: true });
    return res.status(400).json({ error: "sender and recipient IDs are required" });
  }

  const expiry = ready.get(recipientId) || 0;
  if (expiry < Date.now()) {
    if (req.file) fs.rmSync(req.file.path, { force: true });
    return res.status(409).json({ error: "receiver is not armed" });
  }

  if (!req.file) return res.status(400).json({ error: "file is required" });

  const transferId = crypto.randomBytes(18).toString("hex");
  transfers.set(transferId, {
    senderId,
    recipientId,
    name: req.file.originalname || "aerolink-file.bin",
    size: req.file.size,
    path: req.file.path,
    createdAt: Date.now(),
    claimed: false
  });

  res.status(201).json({ ok: true, transferId });
});

app.get("/v1/transfers/:id/file", (req, res) => {
  const t = transfers.get(req.params.id);
  if (!t) return res.status(404).json({ error: "transfer not found" });

  res.download(t.path, t.name, err => {
    fs.rmSync(t.path, { force: true });
    transfers.delete(req.params.id);
    if (err && !res.headersSent) res.status(500).json({ error: "download failed" });
  });
});

setInterval(() => {
  const now = Date.now();
  for (const [id, until] of ready) if (until < now) ready.delete(id);
  for (const [id, t] of transfers) {
    if (now - t.createdAt > 30 * 60 * 1000) {
      fs.rmSync(t.path, { force: true });
      transfers.delete(id);
    }
  }
}, 60_000).unref();

app.listen(PORT, () => console.log("AeroLink relay listening on " + PORT));
