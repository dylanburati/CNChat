/* global window bigInt */
// Begin general utils
function sleep(ms) {
  return new Promise(resolve => setTimeout(resolve, ms));
}

function empty(v, type) {
  if(v === undefined || v === null) return true;
  if(type !== undefined) {
    if(type === 'array') {
      return (!Array.isArray(v) || v.length === 0);
    }
    if(typeof v !== type) return true;
    if(type === 'string') {
      return (v.length === 0);
    }
    if(type === 'object') {
      return (Object.keys(v).length === 0);
    }
    return false;
  }

  return false;
}

function isValidUUID(s) {
  if(typeof s !== 'string' || s.length !== 32) {
    return false;
  }
  for(let i = 0; i < 32; i++) {
    if('0123456789abcdef'.indexOf(s[i]) === -1) {
      return false;
    }
  }
  return true;
}

function typedArrayCopy(src, srcIdx, dest, destIdx, len) {
  for(let i = 0; i < len; i++) {
    dest[destIdx + i] = src[srcIdx + i];
  }
  return dest;
}

function typedArrayCopyAndMap(src, srcIdx, dest, destIdx, len, mapFunc) {
  for(let i = 0; i < len; i++) {
    dest[destIdx + i] = mapFunc(src[srcIdx + i]);
  }
  return dest;
}

function typedArrayFill(arr, idx, len, value) {
  for(let i = 0; i < len; i++) {
    arr[idx + i] = value;
  }
}

function toUTF8Bytes(str) {
  const b256 = [];
  for(let i = 0; i < str.length; i++) {
    const field = str.codePointAt(i);
    if(field <= 0x7F) {
      b256.push(field);
    } else if(field <= 0x7FF) {
      b256.push(0xC0 | (field >> 6));
      b256.push(0x80 | (field & 0x3F));
    } else if(field <= 0xFFFF) {
      b256.push(0xE0 | (field >> 12));
      b256.push(0x80 | ((field >> 6) & 0x3F));
      b256.push(0x80 | (field & 0x3F));
    } else {
      i++;  // skip second character of UTF-16 pair, codePoint contains both
      b256.push(0xF0 | (field >> 18));
      b256.push(0x80 | ((field >> 12) & 0x3F));
      b256.push(0x80 | ((field >> 6) & 0x3F));
      b256.push(0x80 | (field & 0x3F));
    }
  }
  return b256;
}

function fromUTF8Bytes(b256) {
  let out = '';
  for(let i = 0; i < b256.length; i++) {
    let field = b256[i];
    if(field > 0x7F) {
      if((field & 0x20) === 0) {
        field = ((b256[i++] & 0x1F) << 6) | (b256[i] & 0x3F);
      } else if((field & 0x10) === 0) {
        field = ((b256[i++] & 0x0F) << 12) | ((b256[i++] & 0x3F) << 6) | (b256[i] & 0x3F);
      } else if((field & 0x08) === 0) {
        field = ((b256[i++] & 0x07) << 18) | ((b256[i++] & 0x3F) << 12) | ((b256[i++] & 0x3F) << 6) | (b256[i] & 0x3F);
      }
    }
    out += String.fromCodePoint(field);
  }
  return out;
}

function base64decode(str) {
  return fromUTF8Bytes(base64decodebytes(str));
}

const BASE64_ALPHA = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
function base64decodebytes(str) {
  const b64 = [];
  for(let i = 0; i < str.length; i++) {
    const rep = BASE64_ALPHA.indexOf(str[i]);
    if(rep === -1) {
      if(str[i] === '=') break;
      else throw new Error('unknown character in base64 string');
    }
    b64[i] = rep;
  }

  const b256 = [];
  const tail64 = b64.length % 4;
  const tail256 = Math.max(0, tail64 - 1);
  let i256 = 0;
  let i64 = 0;
  for(/* i256 = 0, i64 = 0 */; i64 < b64.length - tail64; i64 += 4) {
    const field = ((b64[i64] & 63) << 18) | ((b64[i64 + 1] & 63) << 12) | ((b64[i64 + 2] & 63) << 6) | (b64[i64 + 3] & 63);
    b256[i256++] = (field >> 16) & 255;
    b256[i256++] = (field >> 8) & 255;
    b256[i256++] = field & 255;
  }
  if(tail256 === 1) {
    const field = ((b64[i64] & 63) << 2) | ((b64[i64 + 1] >> 4) & 63);
    b256[i256] = field & 255;
  } else if(tail256 === 2) {
    const field = ((b64[i64] & 63) << 10) | ((b64[i64 + 1] & 63) << 4) | ((b64[i64 + 2] >> 2) & 63);
    b256[i256++] = (field >> 8) & 255;
    b256[i256] = field & 255;
  }

  return b256;
}

function base64encode(str) {
  return base64encodebytes(toUTF8Bytes(str));
}

function base64encodebytes(b256) {
  const b64 = [];
  const tail = b256.length % 3;
  let i256 = 0;
  let i64 = 0;
  for(/* i256 = 0, i64 = 0 */; i256 < b256.length - tail; i256 += 3) {
    const field = ((b256[i256] & 0xFF) << 16) | ((b256[i256 + 1] & 0xFF) << 8) | (b256[i256 + 2] & 0xFF);
    b64[i64++] = (field >> 18) & 63;
    b64[i64++] = (field >> 12) & 63;
    b64[i64++] = (field >> 6) & 63;
    b64[i64++] = field & 63;
  }
  if(tail === 1) {
    const field = b256[i256] & 0xFF;
    b64[i64++] = (field >> 2) & 63;
    b64[i64] = (field & 3) << 4;
  } else if(tail === 2) {
    const field = ((b256[i256] & 0xFF) << 8) | (b256[i256 + 1] & 0xFF);
    b64[i64++] = (field >> 10) & 63;
    b64[i64++] = (field >> 4) & 63;
    b64[i64] = (field & 15) << 2;
  }
  let s64 = b64.map(e => BASE64_ALPHA[e]).join('');
  if(tail > 0) {
    for(let i = tail; i < 3; i++) {
      s64 += '=';
    }
  }
  return s64;
}

// Begin cryptography utils
const DER_TAGS = {
  Boolean: 0x01,
  Integer: 0x02,
  BitString: 0x03,
  OctetString: 0x04,
  Null: 0x05,
  ObjectId: 0x06,
  Enumerated: 0x0A,
  UTF8String: 0x0C,
  PrintableString: 0x13,
  T61String: 0x14,
  IA5String: 0x16,
  UtcTime: 0x17,
  GeneralizedTime: 0x18,
  GeneralString: 0x1B,
  UniversalString: 0x1C,
  BMPString: 0x1E,
  Sequence: 0x30,
  SequenceOf: 0x30,
  Set: 0x31,
  SetOf: 0x31
};

class DerInterval {
  constructor(tag, skip, count) {
    this.tagIndex = tag;
    this.lenIndex = tag + 1;
    this.lenCount = skip;
    this.valIndex = this.lenIndex + this.lenCount;
    this.valCount = count;
    this.valEnd = this.valIndex + this.valCount;  // exclusive
  }
}

function derEncodeLength(len) {
  let out = [];
  const b1 = len & 255;
  const b2 = (len >> 8) & 255;
  if(b2 === 0) {
    if(b1 > 0x7F) {
      out = [0x81];
    }
    out.push(b1);
    return out;
  }
  const b3 = (len >> 16) & 255;
  if(b3 === 0) {
    out = [0x82, b2, b1];
    return out;
  }
  const b4 = (len >> 24) & 255;
  if(b4 === 0) {
    out = [0x83, b3, b2, b1];
  } else {
    out = [0x84, b4, b3, b2, b1];
  }
  return out;
}

function derEncodeOid(data) {
  let out = [];
  out[0] = DER_TAGS.ObjectId;
  out = out.concat(derEncodeLength(data.length));
  out = out.concat(data);
  return out;
}

function derEncodeInteger(val) {
  if(!bigInt.isInstance(val)) {
    throw new Error('Integer to encode must be bigInt');
  }
  let out = [];
  out[0] = DER_TAGS.Integer;
  let toEncode = val.toArray(256).value;
  if((toEncode[0] & 0x80) !== 0) {
    // Add sign bit
    toEncode = [0].concat(toEncode);
  }
  out = out.concat(derEncodeLength(toEncode.length));
  out = out.concat(toEncode);
  return out;
}

function derEncodeSequence(seq) {
  let out = [];
  out[0] = DER_TAGS.Sequence;
  out = out.concat(derEncodeLength(seq.length));
  out = out.concat(seq);
  return out;
}

function derEncodeBitString(seq) {
  let out = [];
  out[0] = DER_TAGS.BitString;
  out = out.concat(derEncodeLength(seq.length + 1));
  out.push(0);
  out = out.concat(seq);
  return out;
}

function getDerInterval(data, i) {
  i++;  // move index to first length byte
  if((data[i] & 0x80) === 0) {
    // 1-byte length
    return new DerInterval(i - 1, 1, data[i]);
  } else {
    // t-byte length, length data starts at `i + 1`
    const t = (data[i] & 0x7F);
    if(t < 1 || t > 4) {
      throw new Error('Error decoding X.509 encoded key');
    }

    let len = 0;
    for(let j = 1; j <= t; j++) {
      len |= data[i + j] << (8 * (t - j));
    }
    return new DerInterval(i - 1, t + 1, len);
  }
}

function getDerInteger(data, interval) {
  if(!(interval instanceof DerInterval)) {
    throw new Error('Error decoding X.509 encoded key (wrong type provided)');
  }
  if(data[interval.tagIndex] !== DER_TAGS.Integer) {
    throw new Error('Error decoding X.509 encoded key');
  }
  const arr = data.filter((e, i) => (i >= interval.valIndex && (i - interval.valIndex) < interval.valCount));
  return bigInt.fromArray(arr, 256);
}

const DH_DATA_BYTES = [42, 134, 72, 134, 247, 13, 1, 3, 1];
const DH_MODULUS = bigInt('24103124269210325885520760221975' +
              '66074856950548502459942654116941' +
              '95810883168261222889009385826134' +
              '16146732271414779040121965036489' +
              '57050582631942730706805009223062' +
              '73474534107340669624601458936165' +
              '97740410271692494532003787294341' +
              '70325843778659198143763193776859' +
              '86952408894019557734611984354530' +
              '15470437472077499697637500843089' +
              '26339295559968882457872412993810' +
              '12913029459299994792636526405928' +
              '46472097303849472116814344647144' +
              '38488520940127459844288859336526' +
              '896320919633919');
const DH_BASE = bigInt(2);

class DHKeyPair {
  constructor(isEphemeral) {
    this.modBits = DH_MODULUS.bitLength().value;
    this.expBits = Math.max(384, this.modBits >> 1);

    if(isEphemeral) {
      let valid = false;
      while(!valid) {
        const randLen = Math.floor((this.expBits + 7) / 8);
        const randArr = new Uint8Array(randLen);
        const lastByteBits = this.expBits - ((randLen - 1) * 8);
        window.crypto.getRandomValues(randArr);
        const mask = (~(-1 << lastByteBits));
        randArr[0] &= mask;

        this.privKey = bigInt.fromArray(Array(randLen).fill(0).map((e, i) => randArr[i]), 256);
        valid = !(this.privKey.compare(1) < 0 ||
                this.privKey.compare(DH_MODULUS.minus(2)) > 0 ||
                this.privKey.bitLength().compare(this.expBits) !== 0);
      }

      this.pubKey = DH_BASE.modPow(this.privKey, DH_MODULUS);
    }
  }

  static async fromSerialized(privWrapped, pubX509, keyWrapper) {
    let [privIV, privHMAC, privCiphertext] = privWrapped.split(';');
    if(empty(privIV, 'string') || empty(privHMAC, 'string') || empty(privCiphertext, 'string')) {
      throw new Error('Serialized private key is not properly formatted');
    }
    privIV = base64decodebytes(privIV);
    privHMAC = base64decodebytes(privHMAC);
    privCiphertext = base64decodebytes(privCiphertext);
    const pubX509Bytes = base64decodebytes(pubX509);
    const pair = new DHKeyPair(false);
    if(!(keyWrapper instanceof CipherStore)) {
      return false;
    }
    if(!Array.isArray(privCiphertext) || !Array.isArray(pubX509Bytes)) {
      return false;
    }
    if(privCiphertext.findIndex(e => (!Number.isInteger(e) || e < 0 || e > 255)) !== -1) {
      return false;
    }
    if(pubX509Bytes.findIndex(e => (!Number.isInteger(e) || e < 0 || e > 255)) !== -1) {
      return false;
    }

    const privTypedArr = await keyWrapper.decryptBytes(privIV, privHMAC, privCiphertext);
    const privArr = new Array(privTypedArr.length);
    for(let i = 0; i < privTypedArr.length; i++) {
      privArr[i] = privTypedArr[i];
    }
    pair.privKey = bigInt.fromArray(privArr, 256);
    if(pair.privKey.compare(1) < 0 ||
            pair.privKey.compare(DH_MODULUS.minus(2)) > 0 ||
            pair.privKey.bitLength().compare(pair.expBits) !== 0) {
      throw new Error('invalid private key in serialized data');
    }
    pair.pubKey = pair.validateSelfPubKey(pubX509Bytes);

    return pair;
  }

  getPublicEncoded() {
    let algoEnc = derEncodeOid(DH_DATA_BYTES);

    let paramsEnc = derEncodeInteger(DH_MODULUS);
    paramsEnc = paramsEnc.concat(derEncodeInteger(DH_BASE));
    paramsEnc = paramsEnc.concat(derEncodeInteger(bigInt(this.privKey.bitLength())));

    algoEnc = algoEnc.concat(derEncodeSequence(paramsEnc));
    let keyEnc1 = derEncodeSequence(algoEnc);

    let keyEnc2 = derEncodeInteger(this.pubKey);
    keyEnc1 = keyEnc1.concat(derEncodeBitString(keyEnc2));

    const keyEncFinal = derEncodeSequence(keyEnc1);
    return keyEncFinal;
  }

  getPrivate() {
    let toEncode = this.privKey.toArray(256).value;
    if((toEncode[0] & 0x80) !== 0) {
      // Add sign bit
      toEncode = [0].concat(toEncode);
    }
    return toEncode;
  }

  async getPrivateWrapped(keyWrapper) {
    if(!(keyWrapper instanceof CipherStore)) {
      throw new Error('keyWrapper is not a CipherStore');
    }
    const toEncrypt = this.getPrivate();
    const { iv, hmac, ciphertext } = await keyWrapper.encryptBytes(toEncrypt);
    let privWrapped = '';
    privWrapped += base64encodebytes(iv) + ';';
    privWrapped += base64encodebytes(hmac) + ';';
    privWrapped += base64encodebytes(ciphertext);
    return privWrapped;
  }

  validateParty2PubKey(inEnc) {
    // in ----------------------------------------------
    //  der                             key
    //   algId params                    key
    //          modulus base privBits?
    const inEncInterval = getDerInterval(inEnc, 0);
    const derEncInterval = getDerInterval(inEnc, inEncInterval.valIndex);
    const algIDInterval = getDerInterval(inEnc, derEncInterval.valIndex);
    const paramsEncInterval = getDerInterval(inEnc, algIDInterval.valEnd);
    const modulusInterval = getDerInterval(inEnc, paramsEncInterval.valIndex);
    const baseGenInterval = getDerInterval(inEnc, modulusInterval.valEnd);
    const keyEncInterval = getDerInterval(inEnc, derEncInterval.valEnd);
    const keyInterval = getDerInterval(inEnc, keyEncInterval.valIndex + 1);

    if(inEnc[inEncInterval.tagIndex] !== DER_TAGS.Sequence ||
        inEnc[derEncInterval.tagIndex] !== DER_TAGS.Sequence ||
        inEnc[algIDInterval.tagIndex] !== DER_TAGS.ObjectId ||
        inEnc[paramsEncInterval.tagIndex] !== DER_TAGS.Sequence) {
      throw new Error('Error decoding X.509 encoded key');
    }
    for(let i = 0; i < DH_DATA_BYTES.length; i++) {
      if(inEnc[algIDInterval.valIndex + i] !== DH_DATA_BYTES[i]) {
        throw new Error('Error decoding X.509 encoded key');
      }
    }

    const party2Modulus = getDerInteger(inEnc, modulusInterval);
    const party2BaseGen = getDerInteger(inEnc, baseGenInterval);
    if((DH_BASE.compare(party2BaseGen) !== 0) || (DH_MODULUS.compare(party2Modulus) !== 0)) {
      throw new Error('Invalid Diffie-Hellman parameters');
    }

    const party2PubKey = getDerInteger(inEnc, keyInterval);
    if(party2PubKey.compare(2) < 0 ||
        party2PubKey.compare(DH_MODULUS.minus(2)) > 0 ||
        DH_MODULUS.mod(party2PubKey) === 0) {
      throw new Error('Invalid Diffie-Hellman parameters');
    }

    return party2PubKey;
  }

  validateSelfPubKey(inEnc) {
    if(this.privKey === undefined || this.privKey === null || !bigInt.isInstance(this.privKey)) {
      throw new Error('Private key not set');
    }
    const pubKey = this.validateParty2PubKey(inEnc);
    const correctPubKey = DH_BASE.modPow(this.privKey, DH_MODULUS);
    if(correctPubKey.compare(pubKey) !== 0) {
      throw new Error('Public key is incorrect');
    }
    return pubKey;
  }

  generateSecretKey(party2PubKey) {
    const expectedLen = Math.floor((DH_MODULUS.bitLength().value + 7) / 8);
    const secretKey = party2PubKey.modPow(this.privKey, DH_MODULUS);
    const secretKeyBytes = secretKey.toArray(256).value;
    const secretKeyBuffer = new ArrayBuffer(expectedLen);
    const secretKeyBufferView = new Uint8Array(secretKeyBuffer);
    let i = 0;
    if(secretKeyBytes.length < expectedLen) {
      for(i = 0; i < expectedLen - secretKeyBytes.length; i++) {
        secretKeyBufferView[i] = 0;
        secretKeyBytes.splice(0, 0, 0);  // add zero to beginning
      }
    } else if(secretKeyBytes.length === expectedLen + 1 && secretKeyBytes[0] === 0) {
      secretKeyBuffer.splice(0, 1);
    } else if(secretKeyBytes.length !== expectedLen) {
      throw new Error('Key is incorrect size');
    }

    typedArrayCopy(secretKeyBytes, i, secretKeyBufferView, i, expectedLen);
    return secretKeyBuffer;
  }
}

let assertSubtle = false;
class CipherStore {
  constructor(keyBytes, initializeIV = true) {
    if(!assertSubtle) {
      if(!empty(window.crypto)) {
        if(!empty(window.crypto.subtle)) {
          assertSubtle = true;
        } else if(!empty(window.crypto.webkitSubtle)) {
          window.crypto.subtle = window.crypto.webkitSubtle;
          assertSubtle = true;
        }
      }
      if(!assertSubtle) {
        throw new Error('Cryptography support is not available (insecure page or outdated browser)');
      }
    }
    if(initializeIV) {
      this.iv = new ArrayBuffer(16);
      const ivView = new Uint8Array(this.iv);
      window.crypto.getRandomValues(ivView);
    }
    this.keyMat = new ArrayBuffer(32);
    const keyMatView = new Uint8Array(this.keyMat);
    typedArrayCopy(keyBytes, 0, keyMatView, 0, 32);

    this.key = null;
    this.readyPromise = new Promise((resolve, reject) => {
      window.crypto.subtle.importKey(
        'raw', this.keyMat, { name: 'AES-CBC' }, false, ['encrypt', 'decrypt']
      ).then(genKey => {
        this.key = genKey;
        resolve();
      });
    });
  }

  getParamsEncoded() {
    if(empty(this.iv)) {
      throw new Error('IV has not been initialized');
    }
    let out = [];
    const ivView = new Uint8Array(this.iv);
    out[0] = DER_TAGS.OctetString;
    out.push(16);  // length of IV
    typedArrayCopy(ivView, 0, out, 2, 16);
    return out;
  }

  setParamsRandom() {
    this.iv = new ArrayBuffer(16);
    const ivView = new Uint8Array(this.iv);
    window.crypto.getRandomValues(ivView);
    return this.getParamsEncoded();
  }

  async encrypt(str) {
    if(this.iv === undefined || this.iv === null) {
      throw new Error('IV has not been initialized');
    }
    const toEncrypt = toUTF8Bytes(str);
    return this.encryptBytes(toEncrypt);
  }

  async encryptBytes(b256) {
    this.setParamsRandom();
    const ivView = new Uint8Array(this.iv);
    const ivArr = new Array(16);
    typedArrayCopy(ivView, 0, ivArr, 0, 16);
    const encBuffer = new ArrayBuffer(b256.length);
    const encBufferView = new Uint8Array(encBuffer);
    typedArrayCopy(b256, 0, encBufferView, 0, b256.length);

    const outBuffer = await window.crypto.subtle.encrypt(
      { name: 'AES-CBC', iv: this.iv }, this.key, encBuffer
    );
    const outBufferView = new Uint8Array(outBuffer);

    const keyMatView = new Uint8Array(this.keyMat);
    const hmac = {};
    hmac.b = new ArrayBuffer(64 + 16 + outBufferView.length);
    hmac.bView = new Uint8Array(hmac.b);
    typedArrayCopyAndMap(keyMatView, 0, hmac.bView, 0, 32, (byte) => (byte ^ 0x36));
    typedArrayFill(hmac.bView, 32, 32, 0x36);
    typedArrayCopy(ivArr, 0, hmac.bView, 64, 16);
    typedArrayCopy(outBufferView, 0, hmac.bView, 64 + 16, outBufferView.length);

    hmac.bHash = await window.crypto.subtle.digest('sha-256', hmac.b);
    hmac.bHashView = new Uint8Array(hmac.bHash);
    hmac.a = new ArrayBuffer(64 + hmac.bHashView.length);
    hmac.aView = new Uint8Array(hmac.a);
    typedArrayCopyAndMap(keyMatView, 0, hmac.aView, 0, 32, (byte) => (byte ^ 0x5c));
    typedArrayFill(hmac.aView, 32, 32, 0x5c);
    typedArrayCopy(hmac.bHashView, 0, hmac.aView, 64, hmac.bHashView.length);

    hmac.result = await window.crypto.subtle.digest('sha-256', hmac.a);
    hmac.resultView = new Uint8Array(hmac.result);

    return { iv: ivArr, ciphertext: outBufferView, hmac: hmac.resultView };
  }

  async decrypt(ivArr, hmacArr, b256e) {
    const b256 = await this.decryptBytes(ivArr, hmacArr, b256e);
    return fromUTF8Bytes(b256);
  }

  async decryptBytes(ivArr, hmacArr, b256e) {
    if(hmacArr.length !== 32 || ivArr.length !== 16) {
      throw new Error('Incorrect IV or HMAC size');
    }

    const keyMatView = new Uint8Array(this.keyMat);
    const hmac = {};
    hmac.b = new ArrayBuffer(64 + ivArr.length + b256e.length);
    hmac.bView = new Uint8Array(hmac.b);
    typedArrayCopyAndMap(keyMatView, 0, hmac.bView, 0, 32, (byte) => (byte ^ 0x36));
    typedArrayFill(hmac.bView, 32, 32, 0x36);
    typedArrayCopy(ivArr, 0, hmac.bView, 64, ivArr.length);
    typedArrayCopy(b256e, 0, hmac.bView, 64 + ivArr.length, b256e.length);

    hmac.bHash = await window.crypto.subtle.digest('sha-256', hmac.b);
    hmac.bHashView = new Uint8Array(hmac.bHash);
    hmac.a = new ArrayBuffer(64 + hmac.bHashView.length);
    hmac.aView = new Uint8Array(hmac.a);
    typedArrayCopyAndMap(keyMatView, 0, hmac.aView, 0, 32, (byte) => (byte ^ 0x5c));
    typedArrayFill(hmac.aView, 32, 32, 0x5c);
    typedArrayCopy(hmac.bHashView, 0, hmac.aView, 64, hmac.bHashView.length);

    hmac.result = await window.crypto.subtle.digest('sha-256', hmac.a);
    hmac.resultView = new Uint8Array(hmac.result);

    for(let i = 0; i < hmac.resultView.length; i++) {
      if(hmacArr[i] !== hmac.resultView[i]) {
        throw new Error('HMAC-SHA256 failed');
      }
    }

    const iv = new ArrayBuffer(16);
    const ivView = new Uint8Array(iv);
    typedArrayCopy(ivArr, 0, ivView, 0, 16);
    const decBuffer = new ArrayBuffer(b256e.length);
    const decBufferView = new Uint8Array(decBuffer);
    typedArrayCopy(b256e, 0, decBufferView, 0, b256e.length);
    const outBuffer = await window.crypto.subtle.decrypt(
        { name: 'AES-CBC', iv: iv }, this.key, decBuffer
    );
    const outBufferView = new Uint8Array(outBuffer);
    return outBufferView;
  }
}

async function tripleKeyAgree(selfSerializedKeys, otherSerializedKeys, party1, keyWrapper) {
  const keyAgreement = {};
  if(!(keyWrapper instanceof CipherStore)) {
    return false;
  }
  const selfKeys = {};
  const otherKeys = {};
  const sharedSecretBufArr = [];

  selfKeys.identity = await DHKeyPair.fromSerialized(selfSerializedKeys.identity_private, selfSerializedKeys.identity_public, keyWrapper);
  if(!party1) {
    selfKeys.prekey = await DHKeyPair.fromSerialized(selfSerializedKeys.prekey_private, selfSerializedKeys.prekey_public, keyWrapper);
  } else {
    selfKeys.ephemeral = new DHKeyPair(true);
    keyAgreement.key_ephemeral_public = base64encodebytes(selfKeys.ephemeral.getPublicEncoded());
  }

  otherKeys.identity_public = selfKeys.identity.validateParty2PubKey(base64decodebytes(otherSerializedKeys.identity_public));
  if(party1) {
    otherKeys.prekey_public = selfKeys.identity.validateParty2PubKey(base64decodebytes(otherSerializedKeys.prekey_public));
    sharedSecretBufArr.push(selfKeys.identity.generateSecretKey(otherKeys.prekey_public));
    sharedSecretBufArr.push(selfKeys.ephemeral.generateSecretKey(otherKeys.identity_public));
    sharedSecretBufArr.push(selfKeys.ephemeral.generateSecretKey(otherKeys.prekey_public));
  } else {
    otherKeys.ephemeral_public = selfKeys.identity.validateParty2PubKey(base64decodebytes(otherSerializedKeys.ephemeral_public));
    sharedSecretBufArr.push(selfKeys.prekey.generateSecretKey(otherKeys.identity_public));
    sharedSecretBufArr.push(selfKeys.identity.generateSecretKey(otherKeys.ephemeral_public));
    sharedSecretBufArr.push(selfKeys.prekey.generateSecretKey(otherKeys.ephemeral_public));
  }

  const sharedSecret = new ArrayBuffer(sharedSecretBufArr[0].byteLength + sharedSecretBufArr[1].byteLength + sharedSecretBufArr[2].byteLength);
  const sharedSecretView = new Uint8Array(sharedSecret);
  let j = 0;
  for(let i = 0; i < 3; i++) {
    const bufArrView = new Uint8Array(sharedSecretBufArr[i]);
    for(let ji = j; j < ji + bufArrView.length; j++) {
      sharedSecretView[j] = bufArrView[j - ji];
    }
  }
  const keyMat = new ArrayBuffer(16);
  const keyMatV = new Uint8Array(keyMat);
  const hash = await window.crypto.subtle.digest('sha-256', sharedSecret);
  const hashV = new Uint8Array(hash);
  for(let i = 0; i < 16; i++) {
    keyMatV[i] = hashV[i];
  }
  keyAgreement.key_secret = keyMat;
  return keyAgreement;
}

async function wrapKey(keyBytes, keyWrapper) {
  if(!(keyWrapper instanceof CipherStore)) {
    throw new Error('keyWrapper is not a CipherStore');
  }
  const { iv, hmac, ciphertext } = await keyWrapper.encryptBytes(keyBytes);
  let keyWrapped = '';
  keyWrapped += base64encodebytes(iv) + ';';
  keyWrapped += base64encodebytes(hmac) + ';';
  keyWrapped += base64encodebytes(ciphertext);
  return keyWrapped;
}

async function unwrapKey(keyWrapped, keyWrapper) {
  if(!(keyWrapper instanceof CipherStore)) {
    throw new Error('keyWrapper is not a CipherStore');
  }
  let [iv, hmac, ciphertext] = keyWrapped.split(';');
  if(empty(iv, 'string') || empty(hmac, 'string') || empty(ciphertext, 'string')) {
    throw new Error('Initial message is not properly formatted');
  }
  iv = base64decodebytes(iv);
  hmac = base64decodebytes(hmac);
  ciphertext = base64decodebytes(ciphertext);

  const decrypted = await keyWrapper.decryptBytes(iv, hmac, ciphertext);
  return decrypted;
}

async function generateKeyWrapper(pass) {
  const passUTF8 = toUTF8Bytes(pass);
  const securePad = [];

  const inHashBuf = new ArrayBuffer(passUTF8.length);
  const inHashBufV = new Uint8Array(inHashBuf);
  typedArrayCopy(passUTF8, 0, inHashBufV, 0, passUTF8.length);
  const hash = await window.crypto.subtle.digest('sha-256', inHashBuf);
  const hashV = new Uint8Array(hash);

  const keyWrapper = new CipherStore(hashV, true);
  await keyWrapper.readyPromise;
  return {
    storage: base64encodebytes(hashV),
    keyWrapper: keyWrapper
  };
}
/* global window WebSocket localStorage axios */
const commandHandlers = {
  conversation_request(commandResults, session) {
    const conversationRequest = JSON.parse(commandResults);
    session.addConversationFromRequest(conversationRequest).then(toAdd => {
      session.enqueue('conversation_add ' + JSON.stringify(toAdd), 0);
    });
  },

  conversation_ls(commandResults, session) {
    const conversationLS = JSON.parse(commandResults);
    const keysToRequest = [];
    const promises = [];
    conversationLS.forEach(conversationObj => {
      const promise = session.addConversationFromLS(conversationObj).then(conversationStatus => {
        if(conversationStatus === false) {
          if(keysToRequest.indexOf(conversationObj.role1) === -1) {
            keysToRequest.push(conversationObj.role1);
            session.pendingConversations.push(conversationObj);
          }
        } else if(conversationStatus === true) {
          if(session.catSent.indexOf(conversationObj.id) === -1) {
            session.catSent.push(conversationObj.id);
            session.enqueue(`conversation_cat ${conversationObj.id}`, 0);
          }
          if(!empty(session.externalMessageHandlers, 'object') &&
                  !empty(session.externalMessageHandlers.conversation_ls, 'function')) {
            session.externalMessageHandlers.conversation_ls(conversationObj);
          }
        } else if(!empty(conversationStatus, 'string')) {
          // conversationStatus is conversationKeyWrapped
          // response to conversation_set_key will be conversation_ls
          session.enqueue(`conversation_set_key ${conversationObj.id} ${conversationStatus}`, 0);
        }
      });
      promises.push(promise);
    });
    Promise.all(promises).then(promiseResultArr => {
      if(keysToRequest.length > 0) {
        session.enqueue('retrieve_keys_other ' + keysToRequest.join(';'), 0);
      }
    });
  },

  conversation_cat(commandResults, session) {
    const pastMessages = JSON.parse(commandResults);
    if(pastMessages.length > 0) {
      const conversationID = parseInt(pastMessages[0].split(';')[0]);
      const conversation = session.conversations.find(e => (e.id === conversationID));
      if(conversation == null) {
        throw new Error('Unknown conversation');
      }
      pastMessages.forEach(pastMsg => {
        const msgObj = { id: conversationID };
        const msgFields = pastMsg.split(';', 6);
        const [id, from, time, contentType, iv, hmac] = msgFields;
        if(empty(id, 'string') || empty(from, 'string') || empty(time, 'string') ||
                empty(contentType, 'string') || empty(iv, 'string') || empty(hmac, 'string')) {
          return;
        }
        if(parseInt(id) !== conversationID) {
          return;
        }
        const ciphertext = pastMsg.substring(msgFields.reduce((acc, cur) => (acc + cur.length), 0) + 6);
        msgObj.from = from;
        msgObj.time = parseInt(time);
        msgObj.contentType = contentType;
        conversation.cipher.decrypt(
          base64decodebytes(iv), base64decodebytes(hmac), base64decodebytes(ciphertext)
        ).then(msgData => {
          msgObj.data = msgData;

          let msgPushed = false;
          if(!empty(session.externalMessageHandlers, 'object') &&
                  !empty(session.externalMessageHandlers.conversation_cat, 'function')) {
            msgPushed = session.externalMessageHandlers.conversation_cat(conversation, msgObj, session.messages);
          }
          if(!msgPushed) {
            session.messages.push(msgObj);
          }
        });
      });
    }
  },

  retrieve_keys_self(commandResults, session) {
    const keyset = JSON.parse(commandResults);
    session.keysets.push(keyset);
  },

  retrieve_keys_other(commandResults, session) {
    const otherKeysets = JSON.parse(commandResults);
    otherKeysets.forEach(keyset => {
      session.keysets.push(keyset);
      const conversationObj = session.pendingConversations.find(e => (keyset.user === e.role1));
      if(conversationObj != null) {
        session.addConversationFromLS(conversationObj).then(conversationStatus => {
          if(conversationStatus === false) {
            throw new Error('retrieve_keys_self must be called before retrieve_keys_other');
          } else if(conversationStatus === true) {
            if(session.catSent.indexOf(conversationObj.id) === -1) {
              session.catSent.push(conversationObj.id);
              session.pendingConversations = session.pendingConversations.filter(e => (keyset.user !== e.role1));
              session.enqueue(`conversation_cat ${conversationObj.id}`, 0);
            }
          } else if(!empty(conversationStatus, 'string')) {
            // conversationStatus is conversationKeyWrapped
            // response to conversation_set_key will be conversation_ls
            session.pendingConversations = session.pendingConversations.filter(e => (keyset.user !== e.role1));
            session.enqueue(`conversation_set_key ${conversationObj.id} ${conversationStatus}`, 0);
          }
        });
      }
    });
  },

  set_preferences(commandResults, session) {
    const preferences = JSON.parse(commandResults);
    if(!empty(session.externalMessageHandlers, 'object') &&
            !empty(session.externalMessageHandlers.set_preferences, 'function')) {
      session.externalMessageHandlers.set_preferences(preferences);
    }
  },

  user_message(currentMsg, session) {
    // user
    // conversationID;from;time;classes;iv;hmac;messageData
    const msgObj = {};
    const msgFields = currentMsg.split(';', 6);
    const conversationID = parseInt(msgFields[0]);
    const [from, time, contentType, iv, hmac] = msgFields.slice(1);
    if(empty(from, 'string') || empty(time, 'string') || empty(contentType, 'string') ||
            empty(iv, 'string') || empty(hmac, 'string')) {
      return;
    }
    if(conversationID <= 0) {
      return;
    }
    const conversation = session.conversations.find(e => (e.id === conversationID));
    if(conversation == null) {
      throw new Error('Unknown conversation');
    }
    const ciphertext = currentMsg.substring(msgFields.reduce((acc, cur) => (acc + cur.length), 0) + 6);
    msgObj.id = conversationID;
    msgObj.from = from;
    msgObj.time = parseInt(time);
    msgObj.contentType = contentType;
    conversation.cipher.decrypt(
      base64decodebytes(iv), base64decodebytes(hmac), base64decodebytes(ciphertext)
    ).then(msgData => {
      msgObj.data = msgData;

      let msgPushed = false;
      if(!empty(session.externalMessageHandlers, 'object') &&
              !empty(session.externalMessageHandlers.user_message, 'function')) {
        msgPushed = session.externalMessageHandlers.user_message(conversation, msgObj, session.messages);
      }
      if(!msgPushed) {
        session.messages.push(msgObj);
      }
    });
  }
};

class ChatSession {
  constructor(conn, externalMessageHandlers) {
    if(!isValidUUID(conn.data)) {
      throw new Error('Not connected');
    }
    this.uuid = conn.data;

    this.conversations = [];
    this.keysets = [];
    this.messages = [];
    this.pendingConversations = [];
    this.catSent = [];
    this.keyWrapper = null;

    this.externalMessageHandlers = externalMessageHandlers;

    this.websocket = new WebSocket(`wss://${window.location.hostname}:8082/${this.uuid}`);
    this.websocket.onmessage = (m) => {
      this.internalMessageHandler(m.data);
    };
  }

  async enqueueWithContentType(str, contentType, conversationID) {
    if(!(this.websocket instanceof WebSocket) || this.websocket.readyState >= WebSocket.CLOSING) {
      throw new Error('Not connected');
    }
    if(this.websocket.readyState === WebSocket.CONNECTING) {
      await new Promise((resolve, reject) => {
        this.websocket.onopen = resolve;
      });
    }
    if(typeof contentType !== 'string') {
      return false;
    }
    if(str.length === 0) {
      return true;
    }

    if(conversationID === 0) {
      const msg = '0;' + str;
      this.websocket.send(msg);
      return true;
    } else if(conversationID > 0) {
      const conv = this.conversations.find(e => (e.id === conversationID));
      if(conv == null) {
        return false;
      }
      const { iv, hmac, ciphertext } = await conv.cipher.encrypt(str);
      let msg = `${conversationID};${contentType};`;
      msg += base64encodebytes(iv) + ';';
      msg += base64encodebytes(hmac) + ';';
      msg += base64encodebytes(ciphertext);
      this.websocket.send(msg);
      return true;
    } else {
      return false;
    }
  }

  async enqueue(str, conversationID) {
    const result = await this.enqueueWithContentType(str, '', conversationID);
  }

  async addConversationFromRequest(conversationRequest) {
    const self = conversationRequest.find(e => ('identity_private' in e));
    if(self == null) {
      throw new Error('Request is missing required keys');
    }
    const toAdd = { users: [] };
    const userNameList = [self.user];

    const conversationKeyBytes = new Uint8Array(32);
    window.crypto.getRandomValues(conversationKeyBytes);
    const conversationCipher = new CipherStore(conversationKeyBytes);
    await conversationCipher.readyPromise;

    const asyncLoopFunction = async(other) => {
      if(other === self) return;
      userNameList.push(other.user);
      const keyAgreement = await tripleKeyAgree(self, other, true, this.keyWrapper);
      const otherUserObj = {
        user: other.user,
        role: 2,
        key_ephemeral_public: keyAgreement.key_ephemeral_public,
        initial_message: ''
      };
      const initialCipher = new CipherStore(keyAgreement.key_secret);
      await initialCipher.readyPromise;
      const { iv, hmac, ciphertext } = await initialCipher.encryptBytes(conversationKeyBytes);
      otherUserObj.initial_message += base64encodebytes(iv) + ';';
      otherUserObj.initial_message += base64encodebytes(hmac) + ';';
      otherUserObj.initial_message += base64encodebytes(ciphertext);
      toAdd.users.push(otherUserObj);
    };

    const promises = [];
    conversationRequest.forEach(other => {
      promises.push(asyncLoopFunction(other));
    });
    await Promise.all(promises);

    const selfUserObj = {
      user: self.user,
      role: 1,
      key_wrapped: ''
    };
    selfUserObj.key_wrapped = await wrapKey(conversationKeyBytes, this.keyWrapper);
    toAdd.users.push(selfUserObj);
    return toAdd;
  }

  async addConversationFromLS(conversationObj) {
    const toAdd = {
      id: conversationObj.id,
      users: conversationObj.users,
      cipher: null
    };

    if(!empty(conversationObj.key_wrapped, 'string')) {
      // User's part of key exchange is complete
      if(this.conversations.findIndex(e => (e.id === conversationObj.id)) !== -1) {
        // Conversation has already been added
        return true;
      }
      const conversationKeyBytes = await unwrapKey(conversationObj.key_wrapped, this.keyWrapper);
      toAdd.cipher = new CipherStore(conversationKeyBytes);
      await toAdd.cipher.readyPromise;
      this.conversations.push(toAdd);
      return true;
    } else {
      // User's part of key exchange is incomplete
      const self = this.keysets.find(e => ('identity_private' in e));
      const other = this.keysets.find(e => (e.user === conversationObj.role1));
      if(self == null || other == null) {
        // Message handler should request keys from the server and retry
        return false;
      }

      const [iv, hmac, ciphertext] = conversationObj.initial_message.split(';');
      if(empty(iv, 'string') || empty(hmac, 'string') || empty(ciphertext, 'string')) {
        throw new Error('Initial message is not properly formatted');
      }
      const otherUserObj = Object.assign({}, other, { ephemeral_public: conversationObj.key_ephemeral_public });
      const keyAgreement = await tripleKeyAgree(self, otherUserObj, false, this.keyWrapper);
      const initialCipher = new CipherStore(keyAgreement.key_secret);
      await initialCipher.readyPromise;
      const conversationKeyBytes = await initialCipher.decryptBytes(
        base64decodebytes(iv), base64decodebytes(hmac), base64decodebytes(ciphertext)
      );
      toAdd.cipher = new CipherStore(conversationKeyBytes);
      await toAdd.cipher.readyPromise;
      this.conversations.push(toAdd);

      const conversationKeyWrapped = await wrapKey(conversationKeyBytes, this.keyWrapper);
      return conversationKeyWrapped;  // Message handler should upload wrapped key to the server
    }
  }

  internalMessageHandler(message) {
    console.log(message);
    let [conversationID, messageType] = message.split(';', 2);
    conversationID = parseInt(conversationID);
    if(conversationID === 0) {
      // server
      // conversationID;messageType;messageData
      if(messageType === 'command') {
        const messageData = message.substring(conversationID.toString().length + messageType.length + 2);
        const commandIdx = messageData.indexOf(';');
        const command = messageData.substring(0, commandIdx);
        const commandResults = messageData.substring(commandIdx + 1);
        const commandHandler = commandHandlers[command];
        if(!empty(commandHandler, 'function')) {
          commandHandler(commandResults, this);
        }
      }
    } else {
      // user
      // conversationID;from;time;classes;iv;hmac;messageData
      commandHandlers.user_message(message, this);
    }
  }
}

function chatClientBegin(externalMessageHandlers, authEndpoint, authData) {
  return new Promise((resolve2, reject2) => {
    new Promise((resolve, reject) => {
      axios.post(authEndpoint, authData)
        .then((response) => { resolve(response.data); });
    }).then(uuid => {
      const ref = new ChatSession(uuid, externalMessageHandlers);
      ref.keyWrapper = new CipherStore(base64decodebytes(localStorage.getItem('keyWrapper')), false);
      ref.keyWrapper.readyPromise.then(() => {
        ref.enqueue('retrieve_keys_self', 0)
          .then(() => ref.enqueue('conversation_ls', 0))
          .then(() => resolve2(ref));
      });
    });
  });
}
window.chatClientBegin = chatClientBegin;
window.generateKeyWrapper = generateKeyWrapper;
window.base64decodebytes = base64decodebytes;
