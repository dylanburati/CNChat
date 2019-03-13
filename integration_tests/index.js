const stdOut = document.getElementById('stdout');

async function generateKeyWrapper(pass) {
  const passUTF8 = toUTF8Bytes(pass);
  const securePad = [];

  const inHashBuf = new ArrayBuffer(passUTF8.length);
  const inHashBufV = new Uint8Array(inHashBuf);
  for(let i = 0; i < passUTF8.length; i++) {
    inHashBufV[i] = passUTF8[i];
  }
  const hash = await window.crypto.subtle.digest('sha-256', inHashBuf);
  const hashV = new Uint8Array(hash);

  const keyWrapper = new CipherStore(hashV, true);
  await keyWrapper.readyPromise;
  return {
    storage: base64encodebytes(hashV),
  };
}

function println(line) {
  stdOut.textContent += line + '\n';
}

let credentials = {};
let session;
async function test() {
  const keyWrapper = await generateKeyWrapper(credentials.pass);
  localStorage.setItem('keyWrapper', keyWrapper.storage);
  session = await chatClientBegin({}, 'https://localhost:8081', `join ${credentials.name}`);
  println(session.uuid);
}
