/* global window WebSocket axios */
class ChatSession {
  constructor(conn, messageHandlerCallback) {
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

    this.websocket = new WebSocket(`wss://${window.location.hostname}:8082/${this.uuid}`);
    this.websocket.onmessage = (m) => {
      messageHandlerCallback(m.data, this);
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
}

function chatClientBegin(messageHandler) {
  return new Promise((resolve2, reject2) => {
    new Promise((resolve, reject) => {
      axios.post('/backend-chat.php', { command: 'join' })
        .then((response) => { resolve(response.data); });
    }).then(uuid => {
      const ref = new ChatSession(uuid, messageHandler);
      ref.keyWrapper = new CipherStore(base64decodebytes(sessionStorage.getItem('keyWrapper')), false);
      ref.keyWrapper.readyPromise.then(() => {
        ref.enqueue('retrieve_keys_self', 0)
          .then(() => ref.enqueue('conversation_ls', 0))
          .then(() => resolve2(ref));
      });
    });
  });
}
