function toUTF8Bytes(str) {
  var b256 = [];
  for(var i = 0; i < str.length; i++) {
    var field = str.codePointAt(i);
    if(field <= 0x7F) {
      b256.push(field);
    } else if(field <= 0x7FF) {
      b256.push(0xC0 | (field >> 6));
      b256.push(0x80 | (field & 0x3F));
    } else {
      b256.push(0xE0 | (field >> 12));
      b256.push(0x80 | ((field >> 6) & 0x3F));
      b256.push(0x80 | (field & 0x3F));
    }
  }
  return b256;
}

function fromUTF8Bytes(b256) {
  var out = "";
  for(var i = 0; i < b256.length; i++) {
    var field = b256[i];
    if(field > 0x7F) {
      if((field & 0x20) == 0) {
        field = ((b256[i++] & 0x1F) << 6) | (b256[i] & 0x3F);
      } else if((field & 0x10) == 0) {
        field = ((b256[i++] & 0x0F) << 12) | ((b256[i++] & 0x3F) << 6) | (b256[i] & 0x3F);
      }
    }
    out += String.fromCodePoint(field);
  }
  return out;
}

function base64decode(str) {
  return fromUTF8Bytes(base64decodebytes(str));
}

function base64decodebytes(str) {
  var b64 = [];
  var b64alpha = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
  for(var i = 0; i < str.length; i++) {
    var rep = b64alpha.indexOf(str[i]);
    if(rep == -1) {
      if(str[i] == '=') break;
      else throw new Error("unknown character in base64 string");
    }
    b64[i] = rep;
  }
  
  var b256 = [];
  var tail64 = b64.length % 4;
  var tail256 = Math.max(0, tail64 - 1);
  for(var i256 = 0, i64 = 0; i64 < b64.length - tail64; i64 += 4) {
    var field = ((b64[i64] & 63) << 18) | ((b64[i64 + 1] & 63) << 12) | ((b64[i64 + 2] & 63) << 6) | (b64[i64 + 3] & 63);
    b256[i256++] = (field >> 16) & 255;
    b256[i256++] = (field >> 8) & 255;
    b256[i256++] = field & 255;
  }
  if(tail256 == 1) {
    var field = ((b64[i64] & 63) << 2) | ((b64[i64 + 1] >> 4) & 63);
    b256[i256] = field & 255;
  } else if(tail256 == 2) {
    var field = ((b64[i64] & 63) << 10) | ((b64[i64 + 1] & 63) << 4) | ((b64[i64 + 2] >> 2) & 63);
    b256[i256++] = (field >> 8) & 255;
    b256[i256] = field & 255;
  }
  
  return b256;
}

function base64encode(str) {
  return base64encodebytes(toUTF8Bytes(str));
}

function base64encodebytes(b256) {
  var b64 = [];
  var b64alpha = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
  var tail = b256.length % 3;
  for(var i256 = 0, i64 = 0; i256 < b256.length - tail; i256 += 3) {
    var field = ((b256[i256] & 0xFF) << 16) | ((b256[i256 + 1] & 0xFF) << 8) | (b256[i256 + 2] & 0xFF);
    b64[i64++] = (field >> 18) & 63;
    b64[i64++] = (field >> 12) & 63;
    b64[i64++] = (field >> 6) & 63;
    b64[i64++] = field & 63;
  }
  if(tail == 1) {
    var field = b256[i256] & 0xFF;
    b64[i64++] = (field >> 2) & 63;
    b64[i64] = (field & 3) << 4;
  } else if(tail == 2) {
    var field = ((b256[i256] & 0xFF) << 8) | (b256[i256 + 1] & 0xFF);
    b64[i64++] = (field >> 10) & 63;
    b64[i64++] = (field >> 4) & 63;
    b64[i64] = (field & 15) << 2;
  }
  var s64 = b64.map(e=>b64alpha[e]).join("");
  for(var i = tail; i > 0 && i < 3; i++) {
    s64 += '=';
  }
  return s64;
}

var DerTags = {
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
}

function DerInterval(tag, skip, count) {
  this.tagIndex = tag;
  this.lenIndex = tag + 1;
  this.lenCount = skip;
  this.valIndex = this.lenIndex + this.lenCount;
  this.valCount = count;
  this.valEnd = this.valIndex + this.valCount;  // exclusive
  return this;
}

function der_encode_length(len) {
  var out = [];
  b1 = len & 255;
  b2 = (len >> 8) & 255;
  if(b2 == 0) {
    if(b1 > 0x7F) {
      out = [0x81];
    }
    out.push(b1);
    return out;
  }
  b3 = (len >> 16) & 255;
  if(b3 == 0) {
    out = [0x82, b2, b1]
    return out;
  }
  b4 = (len >> 24) & 255;
  if(b4 == 0) {
    out = [0x83, b3, b2, b1];
  } else {
    out = [0x84, b4, b3, b2, b1];
  }
  return out;
}

function der_encode_oid(data) {
  var out = [];
  out[0] = DerTags.ObjectId;
  out = out.concat(der_encode_length(data.length));
  out = out.concat(data);
  return out;
}

function der_encode_integer(val) {
  if(!bigInt.isInstance(val)) throw new Error("Integer to encode must be bigInt");
  var out = [];
  out[0] = DerTags.Integer;
  var toEncode = val.toArray(256).value;
  if((toEncode[0] & 0x80) != 0) {
    // Add sign bit
    toEncode = [0].concat(toEncode);
  }
  out = out.concat(der_encode_length(toEncode.length));
  out = out.concat(toEncode);
  return out;
}

function der_encode_sequence(seq) {
  var out = [];
  out[0] = DerTags.Sequence;
  out = out.concat(der_encode_length(seq.length));
  out = out.concat(seq);
  return out;
}

function der_encode_bit_string(seq) {
  var out = [];
  out[0] = DerTags.BitString;
  out = out.concat(der_encode_length(seq.length + 1));
  out.push(0);
  out = out.concat(seq);
  return out;
}

var DH_data_bytes = [42, 134, 72, 134, 247, 13, 1, 3, 1];
function DHKeyPair() {  
  this.otrModulus = bigInt("24103124269210325885520760221975" +
                "66074856950548502459942654116941" +
                "95810883168261222889009385826134" +
                "16146732271414779040121965036489" +
                "57050582631942730706805009223062" +
                "73474534107340669624601458936165" +
                "97740410271692494532003787294341" +
                "70325843778659198143763193776859" +
                "86952408894019557734611984354530" +
                "15470437472077499697637500843089" +
                "26339295559968882457872412993810" +
                "12913029459299994792636526405928" +
                "46472097303849472116814344647144" +
                "38488520940127459844288859336526" +
                "896320919633919");

  this.otrBase = bigInt(2);
  this.modBits = this.otrModulus.bitLength().value;
  this.expBits = Math.max(384, this.modBits >> 1);

  var valid = false;
  while(!valid) {
    var randLen = Math.floor((this.expBits + 7) / 8);
    var randArr = new Uint8Array(randLen);
    var lastByteBits = this.expBits - ((randLen - 1) * 8);
    window.crypto.getRandomValues(randArr);
    var mask = (~(-1 << lastByteBits));
    randArr[0] &= mask;

    this.privKey = bigInt.fromArray(Array(randLen).fill(0).map((e,i)=>randArr[i]), 256);
    valid = !(this.privKey.compare(1) < 0 ||
      this.privKey.compare(this.otrModulus.minus(2)) > 0 ||
      this.privKey.bitLength().compare(this.expBits) != 0);
  }
  
  this.pubKey = this.otrBase.modPow(this.privKey, this.otrModulus);
}

DHKeyPair.prototype.getPublicEncoded = function() {
  var algoEnc = der_encode_oid(DH_data_bytes);

  var paramsEnc = der_encode_integer(this.otrModulus);
  paramsEnc = paramsEnc.concat(der_encode_integer(this.otrBase));
  paramsEnc = paramsEnc.concat(der_encode_integer(bigInt(this.privKey.bitLength())));

  algoEnc = algoEnc.concat(der_encode_sequence(paramsEnc));
  var keyEnc1 = der_encode_sequence(algoEnc);

  var keyEnc2 = der_encode_integer(this.pubKey);
  keyEnc1 = keyEnc1.concat(der_encode_bit_string(keyEnc2));

  var keyEncFinal = der_encode_sequence(keyEnc1);
  return keyEncFinal;
}

function get_der_interval(data, i) {
  i++;
  if((data[i] & 0x80) == 0) {
    return new DerInterval(i - 1, 1, data[i]);
  } else {
    var t = (data[i] & 0x7F);
    if(t < 1 || t > 4) {
      throw new Error("Error decoding X.509 encoded key");
    }

    var len = 0;
    for(var j = 1; j <= t; j++) {
      len |= data[i + j] << (8 * (t - j));
    }
    return new DerInterval(i - 1, t + 1, len);
  }
}

function get_der_integer(data, interval) {
  if(!(interval instanceof DerInterval)) {
    throw new Error("Error decoding X.509 encoded key (wrong type provided)");
  }
  if(data[interval.tagIndex] != DerTags.Integer) {
    throw new Error("Error decoding X.509 encoded key");
  }
  var arr = data.filter((e, i)=>(i >= interval.valIndex && (i - interval.valIndex) < interval.valCount));
  return bigInt.fromArray(arr, 256);
}

DHKeyPair.prototype.validateParty2PubKey = function(inEnc) {
  // in ----------------------------------------------
  //  der                             key
  //   algId params                    key
  //          modulus base privBits?
  var inEncInterval = get_der_interval(inEnc, 0);
  var derEncInterval = get_der_interval(inEnc, inEncInterval.valIndex);
  var algIDInterval = get_der_interval(inEnc, derEncInterval.valIndex);
  var paramsEncInterval = get_der_interval(inEnc, algIDInterval.valEnd);
  var modulusInterval = get_der_interval(inEnc, paramsEncInterval.valIndex);
  var baseGenInterval = get_der_interval(inEnc, modulusInterval.valEnd);
  var keyEncInterval = get_der_interval(inEnc, derEncInterval.valEnd);
  var keyInterval = get_der_interval(inEnc, keyEncInterval.valIndex + 1);

  if(inEnc[inEncInterval.tagIndex] != DerTags.Sequence ||
      inEnc[derEncInterval.tagIndex] != DerTags.Sequence ||
      inEnc[algIDInterval.tagIndex] != DerTags.ObjectId ||
      inEnc[paramsEncInterval.tagIndex] != DerTags.Sequence) {
    throw new Error("Error decoding X.509 encoded key");
  }
  for(var i = 0; i < DH_data_bytes.length; i++) {
    if(inEnc[algIDInterval.valIndex + i] != DH_data_bytes[i]) {
      throw new Error("Error decoding X.509 encoded key");
    }
  }

  var party2Modulus = get_der_integer(inEnc, modulusInterval);
  var party2BaseGen = get_der_integer(inEnc, baseGenInterval);
  if((this.otrBase.compare(party2BaseGen) != 0) || (this.otrModulus.compare(party2Modulus) != 0)) {
    throw new Error("Invalid Diffie-Hellman parameters");
  }

  var party2PubKey = get_der_integer(inEnc, keyInterval);
  if(party2PubKey.compare(2) < 0 ||
      party2PubKey.compare(this.otrModulus.minus(2)) > 0 ||
      this.otrModulus.mod(party2PubKey) == 0) {
    throw new Error("Invalid Diffie-Hellman parameters");
  }

  return party2PubKey;
}

DHKeyPair.prototype.generateSecretKey = function(party2PubKey) {
  var expectedLen = Math.floor((this.otrModulus.bitLength().value + 7) / 8);
  var secretKey = party2PubKey.modPow(this.privKey, this.otrModulus);
  var secretKeyBytes = secretKey.toArray(256).value;
  var secretKeyBuffer = new ArrayBuffer(expectedLen);
  var secretKeyBufferView = new Uint8Array(secretKeyBuffer);
  var i = 0;
  if(secretKeyBytes.length < expectedLen) {
    for(i = 0; i < expectedLen - secretKeyBytes.length; i++) {
      secretKeyBufferView[i] = 0;
      secretKeyBytes.splice(0, 0, 0);  // add zero to beginning
    }
  } else if(secretKeyBytes.length == expectedLen + 1 && secretKeyBytes[0] == 0) {
    secretKeyBuffer.splice(0, 1);
  } else if(secretKeyBytes.length != expectedLen) {
    throw new Error("Key is incorrect size");
  }

  for(/* i = i */; i < expectedLen; i++) {
    secretKeyBufferView[i] = secretKeyBytes[i];
  }
  return secretKeyBuffer;
}

function CipherStore() {
  this.ivInit = false;
  this.initDH = async function(dhSecret) {
    if(!this.ivInit) {
      this.iv = new ArrayBuffer(16);
      var ivV = new Uint8Array(this.iv);
      window.crypto.getRandomValues(ivV);
      this.ivInit = true;
    }
    var keyMat = new ArrayBuffer(16);
    var keyMatV = new Uint8Array(keyMat);
    var hash = await window.crypto.subtle.digest('sha-256', dhSecret);
    var hashV = new Uint8Array(hash);
    for(var i = 0; i < 16; i++) {
      keyMatV[i] = hashV[i];
    }
    this.key = await window.crypto.subtle.importKey("raw",
                    keyMat,
                    {"name": "AES-CBC"},
                    false,
                    ["encrypt", "decrypt"]);
  };

  this.initRaw = async function(keyArr) {
    if(!this.ivInit) {
      this.iv = new ArrayBuffer(16);
      var ivV = new Uint8Array(this.iv);
      window.crypto.getRandomValues(ivV);
      this.ivInit = true;
    }
    var keyMat = new ArrayBuffer(16);
    var keyMatV = new Uint8Array(keyMat);
    for(var i = 0; i < 16; i++) {
      keyMatV[i] = keyArr[i];
    }
    this.key = await window.crypto.subtle.importKey("raw",
                    keyMat,
                    {"name": "AES-CBC"},
                    false,
                    ["encrypt", "decrypt"]);
  }
}

CipherStore.prototype.getParamsEncoded = function() {
  if(this.iv === undefined || this.iv === null) {
    return false;
  }
  var out = [];
  out[0] = DerTags.OctetString;
  out.push(16);  // length of IV
  var ivV = new Uint8Array(this.iv);
  for(var i = 0; i < 16; i++) {
    out.push(ivV[i]);
  }
  return out;
}

CipherStore.prototype.setParamsRandom = function() {
  if(this.ivInit) return false;
  this.iv = new ArrayBuffer(16);
  var ivV = new Uint8Array(this.iv);
  window.crypto.getRandomValues(ivV);
  this.ivInit = true;
  return this.getParamsEncoded();
}

function isValidUUID(s) {
  if(typeof s !== 'string' || s.length != 32) {
    return false;
  }
  for(var i = 0; i < 32; i++) {
    if("0123456789abcdef".indexOf(s[i]) == -1) {
      return false;
    }
  }
  return true;
}

function tryGetUUID() {
  if(authEndpoint === undefined || authEndpoint === null) {
    throw new Error("authEndpoint undefined");
  }
  var randomUUID = "";
  for(var i = 0; i < 4; i++) {
    var r = Math.floor(Math.random() * Math.pow(2, 31));
    for(var j = 0; j < 8; j++) {
      randomUUID += "0123456789abcdef"[(r >> (7 - j)) & 15];
    }
  }
  return new Promise(function(resolve, reject) {
    $.post(authEndpoint, randomUUID, function(input, textStatus) {
      if(input !== undefined && input !== null) {
        resolve(input);
      } else {
        reject(input);
      }
    });
  });
}

function sendAndReceiveBytes(data, uuid) {
  if(authEndpoint === undefined || authEndpoint === null) {
    throw new Error("authEndpoint undefined");
  }
  return new Promise(function(resolve, reject) {
    $.post(authEndpoint + uuid, base64encodebytes(data), function(input, textStatus) {
      if(input !== undefined && input !== null) {
        resolve(input);
      } else {
        reject(input);
      }
    });
  });
}
