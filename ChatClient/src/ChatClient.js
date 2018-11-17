var sessions = [];
var dispIndex = -1;

function ChatSession(uuid, username) {
  this.uuid = uuid;
  this.resume = false;
  this.joinState = 0;
  if(!this.uuid || this.resume === undefined || this.resume === null) {
    throw new Error("Chat session state not provided");
  }

  this.ciphers = null;
  this.websocket = null;
  this.messages = [];
  this.username = username;
}

ChatSession.prototype.enqueue = async function(str) {
  if(this.uuid == null || this.ciphers == null) {
    return false;
  }
  if(str.length == 0) return;

  var outStr = await this.encrypt(str);
  if(this.websocket == null) {
    throw new Error("Session is not prepared to send messages");
	} else if(this.websocket.readyState < 1) {
    var _sess = this;
    var wsOpenBlock = new Promise(function(resolve, reject) {
      _sess.websocket.onopen = function() {
        _sess.websocket.send(outStr);
        resolve();
      }
    });
    await wsOpenBlock;
    return;
  } else if(this.websocket.readyState == 1) {
		this.websocket.send(outStr);
  } else {
    console.log("websocket is closed");
  }
}

ChatSession.prototype.encrypt = async function(str) {
  var toEncrypt = toUTF8Bytes(str);
  var encBuffer = new ArrayBuffer(toEncrypt.length);
  var encBufferV = new Uint8Array(encBuffer);
  for(var i = 0; i < toEncrypt.length; i++) {
    encBufferV[i] = toEncrypt[i];
  }
  var outBuffer = await window.crypto.subtle.encrypt(
      {name: "AES-CBC", iv: this.ciphers.iv}, this.ciphers.key, encBuffer);
  var outBufferV = new Uint8Array(outBuffer);
  return base64encodebytes(outBufferV);
}

ChatSession.prototype.decrypt = async function(str) {
  var toDecrypt = base64decodebytes(str);
  var decBuffer = new ArrayBuffer(toDecrypt.length);
  var decBufferV = new Uint8Array(decBuffer);
  for(var i = 0; i < toDecrypt.length; i++) {
    decBufferV[i] = toDecrypt[i];
  }
  var inBuffer = await window.crypto.subtle.decrypt(
                {name: "AES-CBC", iv: this.ciphers.iv}, this.ciphers.key, decBuffer);
  var inBufferV = new Uint8Array(inBuffer);
  return fromUTF8Bytes(inBufferV);
}

ChatSession.prototype.handleMessage = function(msg) {
  if(this.joinState < 1) {
    for(let l of msg.split("\n")) {
      if(l.startsWith("Body:")) {
        if(l == "Body:name conflict") {
          this.username = this.uuid.substring(32 - this.username.length - 1);
          this.enqueue("Command:join " + this.username);
        } else if(l == "Body:success") {
          this.joinState = 1;
          var dropEl = $('<a class="dropdown-item dropdown-item-sm chat-conn"></a>');
          dropEl.text(this.username);
          $("#chat-connections-dropdown").prepend(dropEl);
          dropEl.data("index", sessions.findIndex(e => (e == this)));
          dropEl.on("click", populateMessagePanel);
          dropEl.trigger("click");
        }
      }
    }
  } else {
    this.messages.push(msg);
    var _sess = this;
    if(sessions.findIndex(e => (e == _sess)) == dispIndex) {
      var el = '<div class="single-message-container"></div>';
      var el2 = '<div class="bg-ccc message-server" role="alert"></div>';
      el2 = $(el2).append($('<p class="multiline-text"></p>').text(msg));
      el = $(el).append(el2);
      $("#message-panel").append(el);
      window.setTimeout(function() {
        var parentEl = $("#message-container")[0];
        parentEl.scroll(0, parentEl.scrollHeight - parentEl.clientHeight);
      }, 1);
    }
  }
}

async function chatCrypt() {
  if(dataEndpoint === undefined || dataEndpoint === null) {
    throw new Error("dataEndpoint undefined");
  }
	var conn = await tryGetUUID();
  var uuid = conn;
  if(!isValidUUID(uuid)) {
  	return false;
  }
	var session = new ChatSession(uuid, uuid.substring(26));
  sessions.push(session);
  var ciphers = new CipherStore();
  if(session.resume) {
    return false;  // don't use persistence on localhost

  	/*var selfKeyPair = new DHKeyPair();
    var selfPubKeyEnc = selfKeyPair.getPublicEncoded();
    var party2PubKeyEnc = await sendAndReceiveBytes(selfPubKeyEnc, uuid);
    party2PubKeyEnc = base64decodebytes(party2PubKeyEnc);
    var sharedSecret = selfKeyPair.generateSecretKey(selfKeyPair.validateParty2PubKey(party2PubKeyEnc));
    var ephemeral = new Array(16);
    var sharedSecretHash = await window.crypto.subtle.digest('sha-256', sharedSecret);
    var hashV = new Uint8Array(sharedSecretHash);
    for(var i = 0; i < 16; i++) {
    	ephemeral[i] = hashV[i];
    }
    var ephemeralWithWrapped = await sendAndReceiveBytes(ciphers.setParamsRandom(), uuid);
    ephemeralWithWrapped = base64decodebytes(ephemeralWithWrapped);
    var keyAES = new Array(16);
    for(var i = 0; i < 16; i++) {
      keyAES[i] = (ephemeral[i] ^ ephemeralWithWrapped[i]);
    }
    await ciphers.initRaw(keyAES);*/

  } else {
    var selfKeyPair = new DHKeyPair();
    var selfPubKeyEnc = selfKeyPair.getPublicEncoded();
    var party2PubKeyEnc = await sendAndReceiveBytes(selfPubKeyEnc, uuid);
    party2PubKeyEnc = base64decodebytes(party2PubKeyEnc);
    var sharedSecret = selfKeyPair.generateSecretKey(selfKeyPair.validateParty2PubKey(party2PubKeyEnc));
    await ciphers.initDH(sharedSecret);
    await sendAndReceiveBytes(ciphers.getParamsEncoded(), uuid);

  }
  
  session.uuid = uuid;
  session.ciphers = ciphers;
  session.websocket = new WebSocket(dataEndpoint + uuid);
  session.websocket.onmessage = function(recv) {
  	session.decrypt(recv.data).then(session.handleMessage.bind(session));
  }
  return session;
}


$(document).ready(function() {
  $("#chat-connect-new").on("click", function() {
    chatCrypt().then(function(sess) {
      if(!sess.resume) {
        sess.enqueue("Command:join " + sess.username);
      }
    });
  });

  $("#chat-compose").on("change input paste cut", function() {
    var val = $(this).val();
    var lines = val.length - val.replace(/\n/g, "").length + 1;
    setTextAreaHeight(lines);
  });

  $("#chat-send").on("click", function() {
    if(sessions[dispIndex]) {
      var body = $("#chat-compose").val();
      if(new RegExp(/[^\s]+/).exec(body) === null) {
        return;
      }
      var output = body;
      $("#chat-compose").val("");
      $("#chat-compose").trigger("change");
      sessions[dispIndex].enqueue(output);
    }
  });
});

function populateMessagePanel() {
  var index = $(this).data("index");
  dispIndex = index;

  $("#message-panel .single-message-container").remove();
  $(".h4").text("Chat Test: " + sessions[index].username);
  for(let msg of sessions[index].messages) {
    var el = '<div class="single-message-container"></div>';
    var el2 = '<div class="bg-ccc message-server" role="alert"></div>';
    el2 = $(el2).append($('<p class="multiline-text"></p>').text(msg));
    el = $(el).append(el2);
    $("#message-panel").append(el);
  }
  window.setTimeout(function() {
    var parentEl = $("#message-container")[0];
    parentEl.scroll(0, parentEl.scrollHeight - parentEl.clientHeight);
  }, 1);
}

function setTextAreaHeight(lines) {
  var rem = parseInt(window.getComputedStyle($("#chat-compose")[0]).fontSize);
  if(lines > 5) {
    lines = 5;
    $("#chat-compose").css("overflow-y", "auto");
  }
  $("#chat-compose").height(1.5 * lines * rem);
}
