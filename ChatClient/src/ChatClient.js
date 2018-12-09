function sleep(ms) {
  return new Promise(resolve => setTimeout(resolve, ms));
}

function ChatSession(conn, mCallback) {
  this.uuid = conn['data'];
  this.conversations = [];
  this.keysets = [];
  this.messages = [];
  this.pendingConversations = [];
  if(!isValidUUID(this.uuid)) {
    throw new Error("Not connected");
  }

  this.websocket = new WebSocket("wss://" + window.location.host + ":8082/" + this.uuid);
  this.websocket.onmessage = (m) => {
    mCallback(m.data, this).then(_m => {
      if(_m != null) this.messages.push(_m);
    });
  };
}

ChatSession.prototype.enqueue = async function(str, cID) {
  if(this.uuid == null) {
    this.error = "Not connected";
    return false;
  }
  if(str.length == 0) {
    return true;
  }

  if(cID == 0) {
    str = "0;;;" + str;
    if(this.websocket === undefined || this.websocket === null) {
      this.error = "WebSocket uninitialized";
      return false;
    } else if(this.websocket.readyState < 1) {
      var _sess = this;
      var wsOpenBlock = new Promise(function(resolve, reject) {
        _sess.websocket.onopen = function() {
          _sess.websocket.send(str);
          resolve();
        }
      });
      await wsOpenBlock;
      return true;
    } else if(this.websocket.readyState == 1) {
      this.websocket.send(str);
      return true;
    } else {
      this.error = "WebSocket closed. Please reload the page";
      return false;
    }
  } else {
    var sendToConv = this.conversations.find(e => (e['id'] == cID));
    if(sendToConv == null) {
      this.error = "Conversation not found";
      return false;
    }
    var outObj = await sendToConv.cipher.encrypt(str);
    var outStr = "" + cID + ";";
    outStr += base64encodebytes(outObj['iv']) + ";";
    outStr += base64encodebytes(outObj['hmac']) + ";";
    outStr += base64encodebytes(outObj['ciphertext']);
    this.websocket.send(outStr);
  }
}

ChatSession.prototype.addConversationFromRequest = async function(cr) {
  var ca = {users: []};
  var self = cr.find(e => (typeof e['identity_private'] != 'undefined'));
  var skV = new Uint8Array(32);
  window.crypto.getRandomValues(skV);
  var convCipher = new CipherStore(skV, true);
  await convCipher.readyPromise;
  var role2 = false;
  var userNameList = [self.user];

  for(let other of cr) {
    if(other == self) continue;
    userNameList.push(other.user);
    var tka = await tripleKeyAgree(self, other, true);
    var uObj = {'user': other.user, role: (role2 === false ? 2 : 3), key_ephemeral_public: tka['key_ephemeral_public']};
    if(!role2) role2 = true;
    var initialCipher = new CipherStore(tka['key_secret'], true);
    await initialCipher.readyPromise;
    var initialMessageRaw = await initialCipher.encryptBytes(skV);
    uObj['initial_message'] = base64encodebytes(initialMessageRaw['iv']) + ";" +
            base64encodebytes(initialMessageRaw['hmac']) + ";" +
            base64encodebytes(initialMessageRaw['ciphertext']);
    ca.users.push(uObj);
  }
  ca.users.push({user: self.user, role: 1, key_wrapped: base64encodebytes(wrapKey(skV))});
  return ca;
}

ChatSession.prototype.addConversationFromLS = async function(cl) {
  if(cl['key_wrapped'] != null && cl['key_wrapped'].length > 21) {
    if(this.conversations.findIndex(e => (e['id'] == cl['id'])) != -1) {
      return true;
    }
    var convCipher = new CipherStore(unwrapKey(base64decodebytes(cl['key_wrapped'])), true);
    await convCipher.readyPromise;
    this.conversations.splice(0,0, {id: cl['id'], users: cl['users'], cipher: convCipher});
    return true;
  } else {
    var self = this.keysets.find(e => (typeof e['identity_private'] != 'undefined'));
    var other = this.keysets.find(e => (e['user'] == cl['role1']));
    if(self == null || other == null || self == other) {
      return false;
    }
    var otherFull = Object.assign({}, other, {ephemeral_public: cl['key_ephemeral_public']})
    var tka = await tripleKeyAgree(self, otherFull, false);
    var initialMessageRaw = cl['initial_message'].split(";");
    if(initialMessageRaw.length != 3) {
      return false;
    }
    var initialCipher = new CipherStore(tka['key_secret'], true);
    await initialCipher.readyPromise;
    var skV = await initialCipher.decryptBytes(base64decodebytes(initialMessageRaw[0]) /*iv*/,
            base64decodebytes(initialMessageRaw[1]) /*hmac*/,
            base64decodebytes(initialMessageRaw[2]) /*b256e*/);
    var convCipher = new CipherStore(skV, true);
    await convCipher.readyPromise;

    this.conversations.splice(0,0, {id: cl['id'], users: cl['users'], cipher: convCipher});
    return base64encodebytes(wrapKey(skV));
  }
}

function chatClientBegin() {
  return new Promise((resolve2, reject2) => {
    new Promise((resolve, reject) => {
    	axios.post("/backend-chat.php", {command: "join"})
      .then(function(response) { resolve(response.data); })
    })
  	.then(uuid => {
    		var ref = new ChatSession(uuid, messageHandler);
        ref.enqueue("retrieve_keys_self", 0)
        .then(() => ref.enqueue("conversation_ls", 0))
        .then(() => resolve2(ref));
    });
  });
}
