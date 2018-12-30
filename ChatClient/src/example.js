// Example ChatClient script
//
//

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
        session.enqueue('retrieve_keys_other ' + keysToRequest.join(";"));
      }
    });
  },

  conversation_cat(commandResults, session) {
    const pastMessages = JSON.parse(commandResults);
    if(pastMessages.length > 0) {
      const conversationID = parseInt(pastMessages[0].split(';')[0]);
      const conversation = session.conversations.find(e => (e.id === conversationID));
      if(conversation == null) {
        throw new Error("Unknown conversation");
      }
      pastMessages.forEach(pastMsg => {
        const msgObj = { id: conversationID };
        const msgFields = pastMsg.split(';', 6);
        const [ id, from, time, contentType, iv, hmac ] = msgFields;
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
        conversation.cipher.decrypt(base64decodebytes(iv),
                base64decodebytes(hmac),
                base64decodebytes(ciphertext)).then(msgData => {
          msgObj.data = msgData;
          session.messages.push(msgObj);

          // Code to display the message to the screen goes here
          // Should depend on the message classes `contentType`, which is a
          // space separated string specifying the type of message (always 'user')
          // and the sender's preferred format ('markdown' or 'plaintext')
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

  user_message(currentMsg, session) {
    // user
    // conversationID;from;time;classes;iv;hmac;messageData
    const msgObj = {};
    const msgFields = currentMsg.split(';', 6);
    const conversationID = parseInt(msgFields[0]);
    const [ from, time, contentType, iv, hmac ] = msgFields.slice(1);
    if(empty(from, 'string') || empty(time, 'string') || empty(contentType, 'string') ||
            empty(iv, 'string') || empty(hmac, 'string')) {
      return;
    }
    if(conversationID <= 0) {
      return;
    }
    const conversation = session.conversations.find(e => (e.id === conversationID));
    if(conversation == null) {
      throw new Error("Unknown conversation");
    }
    const ciphertext = currentMsg.substring(msgFields.reduce((acc, cur) => (acc + cur.length), 0) + 6);
    msgObj.id = conversationID;
    msgObj.from = from;
    msgObj.time = parseInt(time);
    conversation.cipher.decrypt(base64decodebytes(iv),
            base64decodebytes(hmac),
            base64decodebytes(ciphertext)).then(msgData => {
      msgObj.data = msgData;
      session.messages.push(msgObj);

      // Code to display the message to the screen goes here
      // Should depend on the message classes `contentType`, which is a
      // space separated string specifying the type of message (always 'user')
      // and the sender's preferred format ('markdown' or 'plaintext')
    });
  }
}

function messageHandler(message, session) {
  console.log(message);
  let [ conversationID, messageType ] = message.split(';', 2);
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
        commandHandler(commandResults, session);
      }
    }
  } else {
    // user
    // conversationID;from;time;classes;iv;hmac;messageData
    commandHandlers.user_message(message, session);
  }
}

let session;
chatClientBegin(messageHandler).then(s => { session = s; });
