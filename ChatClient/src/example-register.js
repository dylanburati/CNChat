/* global window document axios */
// Example script that uploads public keys + wrapped private keys
// on user registration

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
    keyWrapper: keyWrapper
  };
}

async function doRegister(formID) {
  const form = document.getElementById(formID);
  const formVal = {};
  Array.from(form.elements).forEach(input => {
    if(input.name === 'name') {
      formVal.name = input.value;
    } else if(input.name === 'pass') {
      formVal.pass = input.value;
    } else if(input.name === 'submit') {
      formVal.submit = input.value;
    }
  });

  formVal.keys = {};
  const identityKey = new DHKeyPair(true);
  const prekey = new DHKeyPair(true);
  const keyWrapperPromise = generateKeyWrapper(formVal.pass);  // string argument passed by value

  // Optionally hash the password after generating the key wrapper.
  // Takes away an attack for people who can inject a malicious 'login reciever' on the server
  // but likely would break compatibility with the previous registration script of your app
  const hashPass = await window.crypto.subtle.digest('sha-256', toUTF8Bytes(formVal.pass));
  const hashPassView = new Uint8Array(hashPass);
  formVal.pass = base64encodebytes(hashPassView);

  keyWrapperPromise.then(keyWrapper => {
    formVal.keys.identity_public = base64encodebytes(identityKey.getPublicEncoded());
    const identityPrivatePromise = identityKey.getPrivateWrapped(keyWrapper.keyWrapper);
    formVal.keys.prekey_public = base64encodebytes(prekey.getPublicEncoded());
    const prekeyPrivatePromise = prekey.getPrivateWrapped(keyWrapper.keyWrapper);

    Promise.all([identityPrivatePromise, prekeyPrivatePromise]).then(function(wrappedPrivateKeys) {
      formVal.keys.identity_private = wrappedPrivateKeys[0];
      formVal.keys.prekey_private = wrappedPrivateKeys[1];

      // The two public keys will be base64 strings
      // The two wrapped private keys will have three base64 strings
      //   (IV, HMAC, and ciphertext) separated by semicolons

      axios.post('/backend-register.php', formVal)
        .then(function(response) {
          // Check registration success
        });
    });
  });
}
