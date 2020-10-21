function sleep(ms) {
  return new Promise(resolve => window.setTimeout(resolve, ms));
}

const stdOut = document.getElementById('stdout');
const stdIn = document.getElementById('stdin');

function println(line) {
  stdOut.textContent += line + '\n';
}

function rangeInclusive(start, end) {
  const r = new Array(end - start + 1).fill(start);
  return r.map((e, i) => e + i);
}

let credentials = {};
let sessionArr = [];
let activeChat = -1;
const handlers = {
  conversation_ls: function(conversation) {
    activeChat = conversation.id;
    println('conversation_ls\n' +
      `  id: ${conversation.id}\n` +
      `  role1: ${conversation.role1}\n` +
      `  users: ${conversation.users}`
    );
    if(conversation.role1 === credentials.name) {
      rangeInclusive(0, conversation.users.length * 10).forEach(secs => {
        const _session = sessionArr[0];
        sleep(secs * 1000).then(() => {
          _session.enqueue(`delay=${secs}`, conversation.id);
        });
      });
    }
  },

  conversation_cat: function(conversation, msgObj) {
    println('conversation_cat\n' +
      `  id: ${msgObj.id}\n` +
      `  from: ${msgObj.from}\n` +
      `  time: ${msgObj.time}\n` +
      `  contentType: ${msgObj.contentType}\n` +
      `  data: ${msgObj.data}`
    );
  },

  user_message: function(conversation, msgObj) {
    if(conversation.id === activeChat) {
      println('user_message\n' +
        `  id: ${msgObj.id}\n` +
        `  from: ${msgObj.from}\n` +
        `  time: ${msgObj.time}\n` +
        `  contentType: ${msgObj.contentType}\n` +
        `  data: ${msgObj.data}`
      );
    }
  }
};


async function login() {
  stdOut.textContent = '';
  credentials = {
    name: document.getElementById('inputName').value,
    pass: document.getElementById('inputPass').value,
  };
  println(`User: ${credentials.name}`);
  println('--');
  const keyWrapper = await generateKeyWrapper(credentials.pass);
  localStorage.setItem('keyWrapper', keyWrapper.storage);
  const sessionLoader = new ChatSessionLoader(
    axios.post('http://localhost:8083', `join ${credentials.name}`)
      .then((response) => {
        const uuid = response.data && response.data.data;
        return {
          uuid: uuid,
          url: 'localhost:8082',
          secure: false
        };
      })
  );
  const currentSession = await sessionLoader.ready;
  currentSession.externalMessageHandlers = handlers;
  if(sessionArr.length > 0) {
    sessionArr[0].externalMessageHandlers = {};
  }
  sessionArr = [currentSession].concat(sessionArr);
  println(`New session @ ${currentSession.websocket.url}`);
  
  let userNameFields = credentials.name.split('.', 2);
  let suffix = credentials.name.substring(userNameFields.reduce((acc, cur) =>
    acc + cur.length, 0) + 1);
  let chatCardinality = parseInt(userNameFields[0]);
  let chatRole = parseInt(userNameFields[1]);
  if(chatRole === 1) {
    let selectUsers = new Array(chatCardinality - 1).fill(0).map((e, i) => {
      return userNameFields[0] + '.' + (i + 2) + suffix;
    });
    currentSession.enqueue('conversation_request ' + selectUsers.join(';'), 0);
  }
}

function send() {
  if(session != null) {
    sessionArr[0].enqueue(stdin.textContent, activeChat);
    stdIn.textContent = '';
  }
}
