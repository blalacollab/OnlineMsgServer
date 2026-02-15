import forge from "node-forge";

const textEncoder = new TextEncoder();
const textDecoder = new TextDecoder();
const subtlePublicKeyCache = new Map();
const forgePublicKeyCache = new Map();
const STORAGE_KEYS = {
  subtlePrivatePkcs8B64: "oms_subtle_private_pkcs8_b64",
  subtlePublicSpkiB64: "oms_subtle_public_spki_b64",
  forgePrivatePem: "oms_forge_private_pem",
  forgePublicSpkiB64: "oms_forge_public_spki_b64"
};

function hasWebCryptoSubtle() {
  return Boolean(globalThis.crypto?.subtle);
}

function toBase64(bytes) {
  let binary = "";
  for (let i = 0; i < bytes.length; i += 1) {
    binary += String.fromCharCode(bytes[i]);
  }
  return btoa(binary);
}

function fromBase64(base64) {
  const binary = atob(base64);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i += 1) {
    bytes[i] = binary.charCodeAt(i);
  }
  return bytes;
}

function bytesToBinaryString(bytes) {
  let result = "";
  for (let i = 0; i < bytes.length; i += 1) {
    result += String.fromCharCode(bytes[i]);
  }
  return result;
}

function binaryStringToBytes(binary) {
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i += 1) {
    bytes[i] = binary.charCodeAt(i);
  }
  return bytes;
}

function concatChunks(chunks) {
  const totalLength = chunks.reduce((sum, item) => sum + item.length, 0);
  const output = new Uint8Array(totalLength);
  let offset = 0;
  chunks.forEach((item) => {
    output.set(item, offset);
    offset += item.length;
  });
  return output;
}

async function importRsaOaepPublicKeySubtle(publicKeyBase64) {
  if (subtlePublicKeyCache.has(publicKeyBase64)) {
    return subtlePublicKeyCache.get(publicKeyBase64);
  }

  const key = await globalThis.crypto.subtle.importKey(
    "spki",
    fromBase64(publicKeyBase64),
    {
      name: "RSA-OAEP",
      hash: "SHA-256"
    },
    false,
    ["encrypt"]
  );
  subtlePublicKeyCache.set(publicKeyBase64, key);
  return key;
}

function importForgePublicKey(publicKeyBase64) {
  if (forgePublicKeyCache.has(publicKeyBase64)) {
    return forgePublicKeyCache.get(publicKeyBase64);
  }

  const asn1 = forge.asn1.fromDer(forge.util.createBuffer(atob(publicKeyBase64)));
  const key = forge.pki.publicKeyFromAsn1(asn1);
  forgePublicKeyCache.set(publicKeyBase64, key);
  return key;
}

function generateForgeKeyPair() {
  return Promise.resolve().then(() => forge.pki.rsa.generateKeyPair({ bits: 2048, workers: 0, e: 0x10001 }));
}

function clearSubtleIdentityFromStorage() {
  try {
    globalThis.localStorage?.removeItem(STORAGE_KEYS.subtlePrivatePkcs8B64);
    globalThis.localStorage?.removeItem(STORAGE_KEYS.subtlePublicSpkiB64);
  } catch {
    // ignore storage failures in private mode
  }
}

function saveSubtleIdentityToStorage(privateKeyRaw, publicKeyRaw) {
  try {
    globalThis.localStorage?.setItem(STORAGE_KEYS.subtlePrivatePkcs8B64, toBase64(new Uint8Array(privateKeyRaw)));
    globalThis.localStorage?.setItem(STORAGE_KEYS.subtlePublicSpkiB64, toBase64(new Uint8Array(publicKeyRaw)));
  } catch {
    // ignore storage failures in private mode
  }
}

async function loadSubtleIdentityFromStorage() {
  try {
    const privatePkcs8B64 = globalThis.localStorage?.getItem(STORAGE_KEYS.subtlePrivatePkcs8B64);
    const publicSpkiB64 = globalThis.localStorage?.getItem(STORAGE_KEYS.subtlePublicSpkiB64);
    if (!privatePkcs8B64 || !publicSpkiB64) {
      return null;
    }

    const privateKeyRaw = fromBase64(privatePkcs8B64);
    const signPrivateKey = await globalThis.crypto.subtle.importKey(
      "pkcs8",
      privateKeyRaw,
      {
        name: "RSASSA-PKCS1-v1_5",
        hash: "SHA-256"
      },
      false,
      ["sign"]
    );

    const decryptPrivateKey = await globalThis.crypto.subtle.importKey(
      "pkcs8",
      privateKeyRaw,
      {
        name: "RSA-OAEP",
        hash: "SHA-256"
      },
      false,
      ["decrypt"]
    );

    return {
      provider: "subtle",
      publicKeyBase64: publicSpkiB64,
      signPrivateKey,
      decryptPrivateKey
    };
  } catch {
    clearSubtleIdentityFromStorage();
    return null;
  }
}

function loadForgeIdentityFromStorage() {
  try {
    const privatePem = globalThis.localStorage?.getItem(STORAGE_KEYS.forgePrivatePem);
    const publicSpkiB64 = globalThis.localStorage?.getItem(STORAGE_KEYS.forgePublicSpkiB64);
    if (!privatePem || !publicSpkiB64) {
      return null;
    }

    const privateKey = forge.pki.privateKeyFromPem(privatePem);
    return {
      provider: "forge",
      publicKeyBase64: publicSpkiB64,
      signPrivateKey: privateKey,
      decryptPrivateKey: privateKey
    };
  } catch {
    return null;
  }
}

function saveForgeIdentityToStorage(privateKey, publicKeyBase64) {
  try {
    const privatePem = forge.pki.privateKeyToPem(privateKey);
    globalThis.localStorage?.setItem(STORAGE_KEYS.forgePrivatePem, privatePem);
    globalThis.localStorage?.setItem(STORAGE_KEYS.forgePublicSpkiB64, publicKeyBase64);
  } catch {
    // ignore storage failures in private mode
  }
}

export function canInitializeCrypto() {
  return hasWebCryptoSubtle() || Boolean(forge?.pki?.rsa);
}

export async function generateClientIdentity() {
  if (hasWebCryptoSubtle()) {
    const cached = await loadSubtleIdentityFromStorage();
    if (cached) {
      return cached;
    }

    const signingKeyPair = await globalThis.crypto.subtle.generateKey(
      {
        name: "RSASSA-PKCS1-v1_5",
        modulusLength: 2048,
        publicExponent: new Uint8Array([1, 0, 1]),
        hash: "SHA-256"
      },
      true,
      ["sign", "verify"]
    );

    const publicKeyRaw = await globalThis.crypto.subtle.exportKey("spki", signingKeyPair.publicKey);
    const privateKeyRaw = await globalThis.crypto.subtle.exportKey("pkcs8", signingKeyPair.privateKey);

    const decryptPrivateKey = await globalThis.crypto.subtle.importKey(
      "pkcs8",
      privateKeyRaw,
      {
        name: "RSA-OAEP",
        hash: "SHA-256"
      },
      false,
      ["decrypt"]
    );
    saveSubtleIdentityToStorage(privateKeyRaw, publicKeyRaw);

    return {
      provider: "subtle",
      publicKeyBase64: toBase64(new Uint8Array(publicKeyRaw)),
      signPrivateKey: signingKeyPair.privateKey,
      decryptPrivateKey
    };
  }

  const cached = loadForgeIdentityFromStorage();
  if (cached) {
    return cached;
  }

  const keyPair = await generateForgeKeyPair();
  const publicPem = forge.pki.publicKeyToPem(keyPair.publicKey);
  const publicKeyBase64 = publicPem
    .replace("-----BEGIN PUBLIC KEY-----", "")
    .replace("-----END PUBLIC KEY-----", "")
    .replace(/\s+/g, "");
  saveForgeIdentityToStorage(keyPair.privateKey, publicKeyBase64);

  return {
    provider: "forge",
    publicKeyBase64,
    signPrivateKey: keyPair.privateKey,
    decryptPrivateKey: keyPair.privateKey
  };
}

export async function signText(privateKey, text) {
  if (hasWebCryptoSubtle() && privateKey?.type === "private") {
    const signature = await globalThis.crypto.subtle.sign(
      "RSASSA-PKCS1-v1_5",
      privateKey,
      textEncoder.encode(text)
    );
    return toBase64(new Uint8Array(signature));
  }

  const md = forge.md.sha256.create();
  md.update(text, "utf8");
  const signatureBinary = privateKey.sign(md);
  return btoa(signatureBinary);
}

export async function rsaEncryptChunked(publicKeyBase64, plainText) {
  if (!plainText) {
    return "";
  }

  const srcBytes = textEncoder.encode(plainText);
  const blockSize = 190;

  if (hasWebCryptoSubtle()) {
    const publicKey = await importRsaOaepPublicKeySubtle(publicKeyBase64);
    const chunks = [];
    for (let i = 0; i < srcBytes.length; i += blockSize) {
      const block = srcBytes.slice(i, i + blockSize);
      const encrypted = await globalThis.crypto.subtle.encrypt({ name: "RSA-OAEP" }, publicKey, block);
      chunks.push(new Uint8Array(encrypted));
    }
    return toBase64(concatChunks(chunks));
  }

  const forgePublicKey = importForgePublicKey(publicKeyBase64);
  let encryptedBinary = "";
  for (let i = 0; i < srcBytes.length; i += blockSize) {
    const block = srcBytes.slice(i, i + blockSize);
    encryptedBinary += forgePublicKey.encrypt(bytesToBinaryString(block), "RSA-OAEP", {
      md: forge.md.sha256.create(),
      mgf1: { md: forge.md.sha256.create() }
    });
  }
  return btoa(encryptedBinary);
}

export async function rsaDecryptChunked(privateKey, cipherTextBase64) {
  if (!cipherTextBase64) {
    return "";
  }

  const secretBytes = fromBase64(cipherTextBase64);
  const blockSize = 256;
  if (secretBytes.length % blockSize !== 0) {
    throw new Error("ciphertext length invalid");
  }

  if (hasWebCryptoSubtle() && privateKey?.type === "private") {
    const chunks = [];
    for (let i = 0; i < secretBytes.length; i += blockSize) {
      const block = secretBytes.slice(i, i + blockSize);
      const decrypted = await globalThis.crypto.subtle.decrypt({ name: "RSA-OAEP" }, privateKey, block);
      chunks.push(new Uint8Array(decrypted));
    }
    return textDecoder.decode(concatChunks(chunks));
  }

  const cipherBinary = atob(cipherTextBase64);
  let plainBinary = "";
  for (let i = 0; i < cipherBinary.length; i += blockSize) {
    const block = cipherBinary.slice(i, i + blockSize);
    plainBinary += privateKey.decrypt(block, "RSA-OAEP", {
      md: forge.md.sha256.create(),
      mgf1: { md: forge.md.sha256.create() }
    });
  }
  return textDecoder.decode(binaryStringToBytes(plainBinary));
}

export function createNonce(size = 18) {
  if (globalThis.crypto?.getRandomValues) {
    const buf = new Uint8Array(size);
    globalThis.crypto.getRandomValues(buf);
    return toBase64(buf);
  }

  if (forge?.random?.getBytesSync) {
    return btoa(forge.random.getBytesSync(size));
  }
  return `${Date.now()}-${Math.random().toString(36).slice(2)}`;
}

export function unixSecondsNow() {
  return Math.floor(Date.now() / 1000);
}
