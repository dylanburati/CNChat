package com.dylanburati.cnchat.server;

import com.dylanburati.cnchat.server.ChatUtils.JSONStructs;
import com.dylanburati.cnchat.server.ChatUtils.MariaDBReader;
import com.dylanburati.cnchat.server.ChatUtils.WebSocketDataframe;
import com.dylanburati.cnchat.server.ChatUtils.WebSocketServer;
import com.jsoniter.JsonIterator;
import com.jsoniter.any.Any;
import com.jsoniter.output.JsonStream;
import com.jsoniter.spi.JsonException;

import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class ClientThread extends Thread {

    private final String first = "0;server;Connected";

    private final ChatServer server;
    private final WebSocketServer wsServer;

    private final String uuid;
    private String userName;
    private final JSONStructs.Preferences userPreferences = new JSONStructs.Preferences();

    private Socket wsSocket;
    private final Object outStreamLock = new Object();
    private StringBuilder continuable = new StringBuilder();

    public ClientThread(ChatServer server, WebSocketServer wsServer, String uuid, String userName) {
        this.server = server;
        this.wsServer = wsServer;
        this.uuid = uuid;
        this.userName = userName;
    }

    public String getUserName() {
        return userName;
    }

    private boolean handleMessage(String message) {
        int conversationID = -1;
        String messageClasses = "";
        int headerParsedL = 0, headerParsedR = -1;
        try {
            headerParsedR = message.indexOf(";");
            conversationID = Integer.parseInt(message.substring(headerParsedL, headerParsedR));
            headerParsedL = headerParsedR + 1;
        } catch(NumberFormatException | IndexOutOfBoundsException e) {
            return true;
        }
        if(conversationID < 0) {
            return true;
        } else if(conversationID == 0) {
            // command for server
            message = message.substring(headerParsedL);
            String outputBody = "";
            if(message.startsWith("conversation_request ")) {
                messageClasses = "command";
                if(message.length() == 21) return true;
                List<String> otherUsers = Arrays.asList(message.substring(21).split(";"));
                if(otherUsers.indexOf(userName) != -1) return true;
                for(int i = 0; i < otherUsers.size(); i++) {
                    if(otherUsers.lastIndexOf(otherUsers.get(i)) != i) {
                        // must be unique
                        return true;
                    }
                }
                if(!server.tryRequestConversation(userName, otherUsers)) {
                    outputBody = "conversation_request;failure";
                } else {
                    StringBuilder responseBuilder = new StringBuilder("[");
                    responseBuilder.append(MariaDBReader.retrieveKeysSelf(userName));
                    for(String u : otherUsers) {
                        String ks = MariaDBReader.retrieveKeysOther(u, userName);
                        if(ks == null) {
                            return true;
                        }
                        responseBuilder.append(",");
                        responseBuilder.append("\n");
                        responseBuilder.append(ks);
                    }
                    responseBuilder.append("]");
                    outputBody = "conversation_request;" + responseBuilder.toString();
                }
            } else if(message.startsWith("conversation_add ")) {
                messageClasses = "command";
                if(message.length() == 17) return true;
                String conversationJson = message.substring(17);
                JSONStructs.Conversation toAdd = server.tryAddConversation(userName, conversationJson);
                for(String u : toAdd.userNameList) {
                    String protocol1 = "0;command;conversation_ls;[" + toAdd.sendToUser(u) + "]";
                    server.messenger.execute(this, protocol1, Collections.singletonList(u), null);
                }
                return true;  // peerMessage above sends reply
            } else if(message.startsWith("conversation_set_key ")) {
                messageClasses = "command";
                if(message.length() == 21) return true;
                try {
                    String[] fields = message.substring(21).split(" ");
                    if(fields.length != 2) return true;
                    int cID = Integer.parseInt(fields[0]);

                    synchronized(server.conversationsLock) {
                        JSONStructs.Conversation c = server.conversations.get(cID);
                        if(c != null) {
                            JSONStructs.ConversationUser u = c.getUser(userName);
                            if(u != null && u.validateWrappedKey(fields[1])) {
                                u.key_wrapped = fields[1];
                                u.key_ephemeral_public = null;
                                u.initial_message = null;
                                c.checkExchangeComplete();
                                MariaDBReader.updateConversationStore(c);
                                outputBody = "conversation_ls;[" + c.sendToUser(userName) + "]";
                            } else {
                                outputBody = "conversation_set_key;failure";
                            }
                        } else {
                            outputBody = "conversation_set_key;failure";
                        }
                    }
                } catch(JsonException e) {
                    e.printStackTrace();
                    return true;
                }
            } else if(message.startsWith("conversation_ls")) {
                messageClasses = "command";
                String conversationLsJson = server.listConversationsForUser(userName);
                outputBody = "conversation_ls;" + conversationLsJson;
            } else if(message.startsWith("conversation_cat ")) {
                messageClasses = "command";
                if(message.length() == 17) return true;
                String[] fields = message.substring(17).split(" ");
                int numPastMessages = 100;
                int cID = -1;
                try {
                    cID = Integer.parseInt(fields[0]);
                } catch(NumberFormatException e) {
                    return true;
                }
                if(fields.length >= 2) {
                    try {
                        numPastMessages = Integer.parseInt(fields[1]);
                    } catch(NumberFormatException ignored) {
                    }
                }
                JSONStructs.Conversation c = null;
                synchronized(server.conversationsLock) {
                    c = server.conversations.get(cID);
                    if(c == null || !c.hasUser(userName)) return true;
                }
                StringBuilder responseBuilder = new StringBuilder();
                List<String> cMessages = MariaDBReader.getMessages(cID, numPastMessages);
                if(cMessages == null) {
                    return true;
                }
                responseBuilder.append(JsonStream.serialize(cMessages));
                outputBody = "conversation_cat;" + responseBuilder.toString();
            } else if(message.startsWith("retrieve_keys_self")) {
                messageClasses = "command";
                String keysetJson = MariaDBReader.retrieveKeysSelf(userName);
                if(keysetJson == null) {
                    return false;
                }
                outputBody = "retrieve_keys_self;" + keysetJson;
            } else if(message.startsWith("retrieve_keys_other ")) {
                messageClasses = "command";
                if(message.length() == 20) return true;
                List<String> otherUsers = Arrays.asList(message.substring(20).split(";"));
                if(otherUsers.indexOf(userName) != -1) return true;
                for(int i = 0; i < otherUsers.size(); i++) {
                    if(otherUsers.lastIndexOf(otherUsers.get(i)) != i) {
                        // must be unique
                        return true;
                    }
                }
                StringBuilder responseBuilder = new StringBuilder("[");
                for(String u : otherUsers) {
                    String keysetJson = MariaDBReader.retrieveKeysOther(u, userName);
                    if(keysetJson == null) {
                        return true;
                    }
                    responseBuilder.append(keysetJson);
                    responseBuilder.append(",");
                    responseBuilder.append("\n");
                }
                if(responseBuilder.length() > 1) {
                    responseBuilder.setLength(responseBuilder.length() - 2);  // remove last comma and line break
                }
                responseBuilder.append("]");
                outputBody = "retrieve_keys_other;" + responseBuilder.toString();
            } else if(message.startsWith("quit")) {
                return false;
            } else if(message.startsWith("set_preferences ")) {
                messageClasses = "command";
                String preferencesJson = message.substring(16);
                JSONStructs.Preferences toUpdate = null;
                try {
                    Any prf = JsonIterator.deserialize(preferencesJson);
                    toUpdate = new JSONStructs.Preferences();
                    toUpdate = prf.bindTo(toUpdate);
                    userPreferences.assign(toUpdate);
                } catch(JsonException e) {
                    e.printStackTrace();
                    return true;
                }
                server.updatePreferenceStore(userName, userPreferences);
                outputBody = "set_preferences;" + JsonStream.serialize(userPreferences);
            } else {
                // invalid server command
                return true;
            }

            enqueue("" + conversationID + ";" + messageClasses + ";" + outputBody);
        } else {
            // user message
            String cipherParams = "";
            String hmac = "";
            String contentType = "";
            try {
                headerParsedR = message.indexOf(";", headerParsedL);
                contentType = message.substring(headerParsedL, headerParsedR);
                headerParsedL = headerParsedR + 1;
                headerParsedR = message.indexOf(";", headerParsedL);
                cipherParams = message.substring(headerParsedL, headerParsedR);
                headerParsedL = headerParsedR + 1;
                headerParsedR = message.indexOf(";", headerParsedL);
                hmac = message.substring(headerParsedL, headerParsedR);
                headerParsedL = headerParsedR + 1;
                message = message.substring(headerParsedL);
            } catch(NumberFormatException | IndexOutOfBoundsException e) {
                return true;
            }

            JSONStructs.Conversation c = null;
            synchronized(server.conversationsLock) {
                c = server.conversations.get(conversationID);
                if(c == null || !c.hasUser(userName)) return true;
            }
            if(contentType.isEmpty()) {
                messageClasses = "user " + (userPreferences.markdown ? "markdown" : "plaintext");
            } else {
                messageClasses = "user " + contentType;
            }

            String outMessage = "" + conversationID + ";" +
                    userName + ";" +
                    System.currentTimeMillis() + ";" +
                    messageClasses + ";" +
                    cipherParams + ";" +
                    hmac + ";" +
                    message;
            server.messenger.execute(this, outMessage, c.userNameList, conversationID);
        }

        return true;
    }

    private void close() {
        if(wsSocket != null) {
            try {
                wsSocket.close();
            } catch(IOException ignored) {
            }
        }
    }

    @Override
    public void run() {
        wsSocket = wsServer.getSocketWhenAvailable(uuid);
        if(wsSocket == null) {
            this.close();
            synchronized(wsServer.authorizedLock) {
                wsServer.authorized.remove(uuid);
            }
            return;
        }

        System.out.format("WebSocket connected @ %s\n", uuid);
        enqueue(first);
        JSONStructs.Preferences toUpdate = server.getStoredPreferences(userName);
        if(toUpdate == null) {
            server.updatePreferenceStore(userName, this.userPreferences);
        } else {
            this.userPreferences.assign(toUpdate);
        }
        try {
            boolean finished = false;
            while(!finished) {
                // getWSMessages blocks until a frame arrives
                String[] messagesEnc = getWSMessages().split("\n");
                for(int i = 0; i < messagesEnc.length && !finished; i++) {
                    String message = messagesEnc[i];
                    synchronized(outStreamLock) {
                        finished = (handleMessage(message) == false);
                    }
                }
            }
        } catch(IOException e) {
            // close();
        } finally {
            close();
        }
    }

    private String getWSMessages() throws IOException {
        InputStream wsIn = wsSocket.getInputStream();

        byte[] header1 = new byte[2];  // opcode, first length
        int pos1 = 0;
        while(pos1 < 2) {
            pos1 += wsSocket.getInputStream().read(header1, pos1, 2 - pos1);
        }

        int opcode = (header1[0] & 0x0F);
        boolean lastFrame = ((header1[0] & 0x80) != 0);
        long dataLen = (header1[1] & 0x7F);
        boolean masked = ((header1[1] & 0x80) != 0);
        if(!masked) {
            this.close();  // Unmasked client-to-server messages are prohibited by the WebSocket protocol
        }

        // Determine length of data section in WebSocket frame
        if(dataLen >= 126) {
            byte[] lengthBuffer = new byte[8];
            int lengthEncSize = (dataLen == 126 ? 2 : 8);  // 126 -> 16-bit unsigned integer, 127 -> 64-bit
            int pos2 = 0;
            while(pos2 < lengthEncSize) {
                pos2 += wsIn.read(lengthBuffer, pos2, lengthEncSize - pos2);
            }
            dataLen = WebSocketDataframe.getLength(lengthBuffer, lengthEncSize);
        }
        if(dataLen > 0x7FFFFFFB) {  // len doesn't include mask
            throw new RuntimeException("Message over 2GB");
        }

        byte[] data = new byte[(int)dataLen + 4];
        int pos = 0;
        while(pos < data.length) {
            pos += wsIn.read(data, pos, data.length - pos);
        }

        String msg = WebSocketDataframe.getText(data);
        if(opcode == 1) {
            if(lastFrame) {
                return msg;
            } else {
                continuable.setLength(0);
                continuable.append(msg);
                return "";
            }
        } else if(opcode == 0) {
            continuable.append(msg);
            if(lastFrame) {
                return continuable.toString();
            } else {
                return "";
            }
        } else if(!lastFrame) {
            throw new RuntimeException("Non-text continuables are not supported");
        } else if(opcode == 9) {
            wsSocket.getOutputStream().write(WebSocketDataframe.toFrame(10, msg));  // if ping then pong
            return "";
        } else if(opcode == 8) {
            close();  // quit message
            return "";
        } else {
            return "";
        }
    }

    public void enqueue(String outMessage) {
        if(wsSocket == null) {
            return;
        }
        byte[] wsOutEnc = WebSocketDataframe.toFrame(1, outMessage);
        try {
            synchronized(outStreamLock) {
                wsSocket.getOutputStream().write(wsOutEnc);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
} // end class ClientThread