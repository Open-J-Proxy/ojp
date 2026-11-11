/**
 * Computes a stable SHA-256 hash over every proto/*.proto file (sorted by name, name+content
 * both hashed so a rename alone still changes the digest).
 */
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const PROTO_DIR = path.resolve(__dirname, '../proto');

function computeProtoHash() {
  const files = fs.readdirSync(PROTO_DIR).filter((file) => file.endsWith('.proto')).sort();
  const hash = crypto.createHash('sha256');
  for (const file of files) {
    hash.update(file);
    hash.update(fs.readFileSync(path.join(PROTO_DIR, file)));
  }
  return hash.digest('hex');
}

module.exports = { computeProtoHash, PROTO_DIR };
