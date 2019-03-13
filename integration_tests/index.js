const stdOut = document.getElementById('stdout');
const stdIn = document.getElementById('stdin');

function println(line) {
  stdOut.textContent += line + '\n';
}

const handlers = {
  conversation_ls: function(conversation) {
    activeChat = conversation.id;
    println('conversation_ls\n' +
      `  id: ${conversation.id}\n` +
      `  users: ${conversation.users}`
    );
  },

  conversation_cat: function(conversation, msgObj, processedArr) {
    println('conversation_cat\n' +
      `  id: ${msgObj.id}\n` +
      `  from: ${msgObj.from}\n` +
      `  time: ${msgObj.time}\n` +
      `  contentType: ${msgObj.contentType}\n` +
      `  data: ${msgObj.data}`
    );
    return false;
  },

  user_message: function(conversation, msgObj, processedArr) {
    println('user_message\n' +
      `  id: ${msgObj.id}\n` +
      `  from: ${msgObj.from}\n` +
      `  time: ${msgObj.time}\n` +
      `  contentType: ${msgObj.contentType}\n` +
      `  data: ${msgObj.data}`
    );
    return false;
  }
}


let credentials = {};
let session;
let activeChat = -1;
async function login() {
  session = null;
  stdOut.textContent = '';
  const credentials = {
    name: document.getElementById('inputName').value,
    pass: document.getElementById('inputPass').value,
  };
  println(`User: ${credentials.name}`);
  println('--');
  const keyWrapper = await generateKeyWrapper(credentials.pass);
  localStorage.setItem('keyWrapper', keyWrapper.storage);
  session = await chatClientBegin(handlers, 'https://localhost:8083', `join ${credentials.name}`);
  println(`New session @ ${session.websocket.url}`);
  
  let userNameFields = credentials.name.split('.', 2);
  let suffix = credentials.name.substring(userNameFields.reduce((acc, cur) =>
    acc + cur.length, 0) + 1);
  let chatCardinality = parseInt(userNameFields[0]);
  let chatRole = parseInt(userNameFields[1]);
  if(chatRole === 1) {
    let selectUsers = new Array(chatCardinality - 1).fill(0).map((e, i) => {
      return userNameFields[0] + '.' + (i + 2) + suffix;
    });
    session.enqueue('conversation_request ' + selectUsers.join(';'), 0);
  }
}

function send() {
  if(session != null) {
    session.enqueue(stdin.textContent, activeChat);
    stdin.textContent = '';
  }
}
