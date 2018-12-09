## Cyber Naysh Chat

CNChat is a simple end-to-end encrypted chat application.
The server runs on Java and can accept any number of clients.
The client is written in JavaScript, and is intended to run in the browser.

Currently, the server stores encrypted messages and conversation data
using [jsoniter](https://github.com/json-iterator/java). Some data is
stored in an SQL database, and accessed by the MariaDB Java client.

#### Encryption
 - CNChat uses a simplified version of
   [X3DH](https://signal.org/docs/specifications/x3dh), the key exchange
   protocol used for Signal.
 - The Diffie-Hellman keys used are 1536 bits, and together they are used
   to generate 3 shared secrets, which are hashed to obtain an initial key.
   In a group chat, each user will have a different initial key.
 - The creator of the conversation generates a fully random AES-256 key,
   and encrypts it with the initial key. This is the conversation key.
 - Each member of the conversation completes their part of the exchange
   by generating the initial key, decrypting the conversation key and
   uploading the wrapped conversation key to the server.
    - The key used to wrap the conversation key, as well as the private DH
	  keys, is derived from a password which the server does not have
	  access to. This allows the server to be used for storage without
	  compromising messages to people with access to the server.
 - Messages must include the IV used for encryption and an HMAC-SHA256 code along with the encrypted message data.
