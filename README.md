## Cyber Naysh Chat  [![CircleCI](https://circleci.com/gh/dylanburati/CNChat.svg?style=svg)](https://circleci.com/gh/dylanburati/CNChat)

CNChat is a simple end-to-end encrypted chat application.
The server runs on Java and can accept any number of clients.
The client is written in JavaScript, and is intended to run in the browser.

In order to allow users to create conversations with offline users, the server
needs to read from a database of cryptographic keys, which each user provides with
two public keys and two *wrapped* private keys when they register.

The documentation goes in depth on [the key exchange protocol](https://dylanburati.github.io/CNChat/key-exchange.html) used, [how to run the ChatServer](https://dylanburati.github.io/CNChat/server.html), and [how to run the ChatClient](https://dylanburati.github.io/CNChat/client.html).
