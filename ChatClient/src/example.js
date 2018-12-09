// Example ChatClient script
//
//

async function messageHandler(m, sess) {
  console.log(m);
  var mObj = {'id': -1};
  var mFields = m.split(";", 2);
  mObj['id'] = parseInt(mFields[0]);
  if(mObj['id'] == 0) {
    // server
    // conversationID;classes;message
    if(mFields[1] == "command") {
      var message = m.substring(mFields.reduce((a, b) => a + b.length, 0) + mFields.length);
      var commandIdx = message.indexOf(";");
      var command = message.substring(0, commandIdx);
      var commandResults = message.substring(commandIdx + 1);
      if(command == "conversation_request") {
        try {
          var cr = JSON.parse(commandResults);
          var ca = await sess.addConversationFromRequest(cr);
          sess.enqueue("conversation_add " + JSON.stringify(ca), 0);
        } catch(ignoreErr) {
        }
      } else if(command == "conversation_ls") {
        var ls = JSON.parse(commandResults);
        var keyReqs = {};
        for(let conv of ls) {
          var keysetsReady = false;
          var wrk = await sess.addConversationFromLS(conv);
          if(wrk === false) {
            sess.pendingConversations.push(conv);
            keyReqs[conv['role1']] = 1;
          } else if(wrk !== true) {
            sess.enqueue("conversation_set_key " + conv['id'] + " " + wrk, 0);
          }
        }
        var toRequest = Object.keys(keyReqs).join(";");
        if(toRequest.length > 0) {
          sess.enqueue("retrieve_keys_other " + toRequest, 0)
        } else if(sess.pendingConversations.length == 0) {
          var toCat = sess.conversations.map(e => e['id']);
          new Promise((resolve, reject) => {
            for(let catID of toCat) {
              if(sess.conversations.findIndex(e => (e['id'] == catID)) == -1) {
                continue;
              }
              sess.enqueue("conversation_cat " + catID, 0);
              sleep(300);
            }
          });
        }
      } else if(command == "conversation_cat") {
        var pastMessages = JSON.parse(commandResults);
        if(pastMessages.length > 0) {
          var pmcID = parseInt(pastMessages[0].split(";")[0]);
          var conv = sess.conversations.find(e => (e['id'] == pmcID));
          if(conv == null) return null;
          for(let pm of pastMessages) {
            pmObj = {id: pmcID};
            var pmFields = pm.split(";", 6);
            var pmessage = pm.substring(pmFields.reduce((a, b) => a + b.length, 0) + pmFields.length);
            pmObj['from'] = pmFields[1];
            pmObj['time'] = parseInt(pmFields[2]);
            pmObj['data'] = await conv.cipher.decrypt(base64decodebytes(pmFields[4]) /*iv*/,
                    base64decodebytes(pmFields[5]) /*hmac*/,
                    base64decodebytes(pmessage) /*b256e*/);
            sess.messages.push(pmObj);
          }
        }
      } else if(command == "retrieve_keys_self") {
        var keyset = JSON.parse(commandResults);
        sess.keysets.push(keyset);
      } else if(command == "retrieve_keys_other") {
        var otherKeysets = JSON.parse(commandResults);
        for(let keyset of otherKeysets) {
          sess.keysets.push(keyset);
        }
        if(otherKeysets.length > 0) {
          for(var i = 0; i < sess.pendingConversations.length; i++) {
            var wrk = await sess.addConversationFromLS(sess.pendingConversations[i]);
            if(wrk !== false) {
              var cID = sess.pendingConversations.splice(i, 1)[0]['id'];
              i--;
              if(wrk !== true) {
                sess.enqueue("conversation_set_key " + cID + " " + wrk, 0);
              }
            }
          }
        }
      }
    }
    return null;
  } else {
    // user
    // conversationID;from;time;classes;iv;hmac;message
    var conv = sess.conversations.find(e => (e['id'] == mObj['id']));
    if(conv == null) return null;
    mFields = m.split(";", 6);
    var message = m.substring(mFields.reduce((a, b) => a + b.length, 0) + mFields.length);
    mObj['from'] = mFields[1];
    mObj['time'] = parseInt(mFields[2]);
    mObj['data'] = await conv.cipher.decrypt(base64decodebytes(mFields[4]) /*iv*/,
            base64decodebytes(mFields[5]) /*hmac*/,
            base64decodebytes(message) /*b256e*/);
    sess.messages.push(mObj);

    // Code to display the message to the screen goes here
    // Should depend on the message classes `mFields[3]`, which is a
    // space separated string specifying the type of message and the sender's
    // preferred format (markdown or plaintext)
  }
}

var session;
$(document).ready(function() {
  chatClientBegin().then(s => { session = s; });
});
