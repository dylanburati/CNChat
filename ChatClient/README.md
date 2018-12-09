## ChatClient

#### 1. Initial setup
 - The ChatClient needs to obtain a session ID from the server by sending an HTTP POST message containing the command:
   `join <username>`
 - The HTTP request should be sent by a proxy to port 8081 on the server, after verifying that the user is logged in and
   has uploaded the required cryptographic key pairs (see Keysets below).
 - The HTTP response will be a 32-character hexadecimal string, referred to as `uuid`

#### 2. WebSocket
 - The WebSocket is used for all interactions with the server except for joining. It is constructed like this:
   `session.websocket = new WebSocket("wss://example.com:8082/" + session.uuid);`
 - If the WebSocket closes, the initial setup should be repeated.

#### ChatSession object
 - The ChatSession object is defined in [ChatClient.js](./src/ChatClient.js).
 - It stores the user's conversations, messages and keysets
 - It controls the addition of conversations that the user is a member of, but it delegates the reading
   of messages to the input function `messageHandler`
 - It controls the sending of messages using the function `enqueue(string, conversationID)`
 
#### Conversations
 - Conversation data is downloaded from the server at the beginning of the session. Without knowledge of conversations,
   the client can only send messages to conversation 0 (the unencrypted link with the server). It also can't decrypt
   any messages from other conversations.
 - Each conversation has a positive integer `id`, an array of strings `users`, and an AES-256 key `cipher`
 - To create a conversation, your client needs to send the command `"conversation_request <user_list>"` to the server,
   where `user_list` is a semicolon-delimited list of user names.

#### Keysets
 - Each user must have a keyset on the server before joining the chat. The keyset consists of two Diffie-Hellman key pairs.
   For each, the public key is serialized using X.509 followed by base64 encoding. The private key is *wrapped* using a secure pad,
   which should be created using iterated hashes of the user's password, and then base64 encoded. The operations involving
   the DH key pairs are defined in [ChatUtils.js](./src/ChatUtils.js).
 - The user's own keyset is downloaded at the beginning of the session, because it is required in order to start new
   conversations. Other keysets are downloaded if the user tries to create a conversation or if someone else creates a
   conversation before or during the session.
   
#### Messages
 - The array of messages kept by the ChatSession object holds an object for every user message. The object contains
   the conversation `id`, an integer `time`, a string `from`, and a string `data`.
 - The format of the message sent by the server is determined by whether it is a server message (`id == 0`) or a user message.
   The method for parsing messages is defined in [example.js](./src/example.js).
 
 
#### Libraries used
BigInteger.js: [Github](https://github.com/peterolson/BigInteger.js)  [NPM](https://www.npmjs.com/package/big-integer) \
Axios: [Github](https://github.com/axios/axios)  [NPM](https://www.npmjs.com/package/axios)
