import ChatUtils.*;
import com.jsoniter.JsonIterator;
import com.jsoniter.any.Any;
import com.jsoniter.output.EncodingMode;
import com.jsoniter.output.JsonStream;
import com.jsoniter.spi.DecodingMode;
import com.jsoniter.spi.JsonException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import javax.xml.bind.DatatypeConverter;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static java.nio.charset.StandardCharsets.UTF_8;

interface peerUpdateCompat<T> {
    void execute(T thread, String message, List<String> specify, Integer addToHistory);
}

public class ChatServer {

    private static HttpServer authServer;
    private static WebSocketServer wsServer;
    private static volatile Map<Integer, JSONStructs.Conversation> conversations = new HashMap<>();
    private static final Object conversationsLock = new Object();
    private static final String conversationStorePath = new File(ChatServer.class.getProtectionDomain().getCodeSource().getLocation().getFile()).
            getParent() + System.getProperty("file.separator") + "conversations.json";
    private static volatile Map<Integer, String> conversationStore = new HashMap<>();
    private static ExecutorService storeWriter = Executors.newSingleThreadExecutor();

    private static Map<Integer, List<String>> messageStore = new HashMap<>();
    private static final Object messagesLock = new Object();
    private static final String messageStorePathPrefix = new File(ChatServer.class.getProtectionDomain().getCodeSource().getLocation().getFile()).
            getParent() + System.getProperty("file.separator") + "messages";
    private static final String messageStorePathSuffix = ".json";

    private static volatile Map<String, String> userNamesMap = new HashMap<>();
    private static final Object userNamesMapLock = new Object();

    private static void updateConversationStore() {
        String[] toWrite = conversationStore.values().toArray(new String[0]);
        if(toWrite.length > 0) {
            storeWriter.submit(() -> {
                try(PrintWriter history = new PrintWriter(new OutputStreamWriter(new FileOutputStream(conversationStorePath), UTF_8), true)) {
                    history.write("[");
                    for(int j = 0; j < toWrite.length - 1; j++) {
                        history.write(toWrite[j]);
                        history.write(",\n");
                    }
                    history.write(toWrite[toWrite.length - 1]);
                    history.write("]");
                } catch(IOException e) {
                    e.printStackTrace();
                }
            });
        }
    }

    private static void updateConversationStore(Integer... changed) {
        for(Integer i : changed) {
            conversationStore.put(i, JsonStream.serialize(conversations.get(i)));
        }
        updateConversationStore();
    }

    private static void storeConversation(Integer i, JSONStructs.Conversation o, boolean write) {
        conversations.put(i, o);
        conversationStore.put(i, JsonStream.serialize(o));
        if(write) {
            updateConversationStore();
        }
    }

    private static void updateMessageStore(Integer cID) {
        String[] toWrite = messageStore.get(cID).toArray(new String[0]);
        if(toWrite.length > 0) {
            storeWriter.submit(() -> {
                try(PrintWriter history = new PrintWriter(new OutputStreamWriter(new FileOutputStream(
                        messageStorePathPrefix + cID + messageStorePathSuffix), UTF_8), true)) {
                    history.write(JsonStream.serialize(toWrite));
                } catch(IOException e) {
                    e.printStackTrace();
                }
            });
        }
    }

    public static void main(String[] args) throws IOException {

        class ClientThread extends Thread {

            private String first = "0;;server;Connected";

            private final String uuid;
            private final peerUpdateCompat<ClientThread> peerMessage;
            String userName;
            private boolean markDown = false;
            private String userDataPath = null;

            private Socket wsSocket;
            private final Object outStreamLock = new Object();
            private StringBuilder continuable = new StringBuilder();

            private ClientThread(String uuid, String userName, peerUpdateCompat<ClientThread> peerMessage) {
                this.uuid = uuid;
                this.userName = userName;
                this.peerMessage = peerMessage;
            }

            private boolean handleMessage(String message) {
                int conversationID = -1;
                String hmac = "";
                String cipherParams = "";
                String messageClasses = "";
                try {
                    int f1 = message.indexOf(";");
                    conversationID = Integer.parseInt(message.substring(0, f1));
                    int f2 = message.indexOf(";", f1 + 1);
                    cipherParams = message.substring(f1 + 1, f2);
                    int f3 = message.indexOf(";", f2 + 1);
                    hmac = message.substring(f2 + 1, f3);
                    message = message.substring(f3 + 1);
                } catch(NumberFormatException | IndexOutOfBoundsException e) {
                    return true;
                }
                if(conversationID < 0) {
                    return true;
                } else if(conversationID == 0) {
                    String outputBody = "";
                    if(message.startsWith("help")) {
                        messageClasses = "server";
                        outputBody = "Commands start with a colon (:)" +
                                "\n:status sends you key info\n";
                    } else if(message.startsWith("status")) {
                        messageClasses = "server";
                        outputBody = "<< Status >>" +
                                "\nUsername: " + userName +
                                "\nFormat: " + (markDown ? "Markdown" : "plain text") +
                                "\n :format for Markdown" +
                                "\n :unformat for plain text";
                    } else if(message.startsWith("conversation_request ")) {
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
                        boolean collision = false;
                        synchronized(conversationsLock) {
                            for(JSONStructs.Conversation existing : conversations.values()) {
                                collision = existing.users.length == (otherUsers.size() + 1);
                                for(String existingUser : existing.userNameList) {
                                    if(!userName.equals(existingUser) &&
                                            otherUsers.indexOf(existingUser) == -1) {
                                        collision = false;
                                    }
                                }
                            }
                        }
                        if(collision) {
                            outputBody = "conversation_request;failure";
                        } else {
                            StringBuilder _outputBody = new StringBuilder("[");
                            _outputBody.append(MariaDBReader.retrieveKeysSelf(userName));
                            for(String u : otherUsers) {
                                String ks = MariaDBReader.retrieveKeysOther(u, userName);
                                if(ks == null) {
                                    return true;
                                }
                                _outputBody.append(",");
                                _outputBody.append("\n");
                                _outputBody.append(ks);
                            }
                            _outputBody.append("]");
                            outputBody = "conversation_request;" + _outputBody.toString();
                        }
                    } else if(message.startsWith("conversation_add ")) {
                        messageClasses = "command";
                        if(message.length() == 17) return true;
                        String conversationJson = message.substring(17);
                        JSONStructs.Conversation toAdd = null;
                        try {
                            Any conv = JsonIterator.deserialize(conversationJson);
                            toAdd = new JSONStructs.Conversation();
                            toAdd = conv.bindTo(toAdd);
                            List<String> cUsers = new ArrayList<>();
                            for(JSONStructs.ConversationUser u : toAdd.users) {
                                cUsers.add(u.user);
                            }
                            if(cUsers.indexOf(userName) == -1) return true;
                            for(int i = 0; i < cUsers.size(); i++) {
                                if(cUsers.lastIndexOf(cUsers.get(i)) != i) {
                                    // must be unique
                                    return true;
                                }
                            }
                            toAdd.exchange_complete = false;
                            toAdd.crypt_expiration = System.currentTimeMillis() + (14 * 24 * 3600 * 1000);
                            synchronized(conversationsLock) {
                                if(conversations.size() == 0)
                                    toAdd.id = 1;
                                else
                                    toAdd.id = Collections.max(conversations.keySet()) + 1;
                                if(toAdd.validateNew(userName)) {
                                    boolean collision = false;
                                    for(JSONStructs.Conversation existing : conversations.values()) {
                                        collision = existing.users.length == (cUsers.size());
                                        for(String existingUser : existing.userNameList) {
                                            if(cUsers.indexOf(existingUser) == -1) {
                                                collision = false;
                                            }
                                        }
                                    }
                                    if(collision) {
                                        return true;
                                    }
                                    storeConversation(toAdd.id, toAdd, true);
                                }
                                synchronized(messagesLock) {
                                    messageStore.put(toAdd.id, new ArrayList<>());
                                }
                            }
                        } catch(JsonException e) {
                            e.printStackTrace();
                            return true;
                        }
                        for(String u : toAdd.userNameList) {
                            String protocol1 = "0;command;conversation_ls;[" + toAdd.sendToUser(u) + "]";
                            peerMessage.execute(this, protocol1, Collections.singletonList(u), null);
                        }
                        return true;  // peerMessage above sends reply
                    } else if(message.startsWith("conversation_set_key ")) {
                        messageClasses = "command";
                        if(message.length() == 21) return true;
                        try {
                            String[] fields = message.substring(21).split(" ");
                            if(fields.length != 2) return true;
                            int cID = Integer.parseInt(fields[0]);
                            if(!fields[1].matches("[0-9A-Za-z+/]*[=]{0,2}") || fields[1].length() < 21) {
                                // Non base-64 or fewer than 128 bits
                                return true;
                            }
                            synchronized(conversationsLock) {
                                JSONStructs.Conversation c = conversations.get(cID);
                                if(c != null) {
                                    JSONStructs.ConversationUser u = c.getUser(userName);
                                    if(u != null) {
                                        u.key_wrapped = fields[1];
                                        u.key_ephemeral_public = null;
                                        u.initial_message = null;
                                        c.checkExchangeComplete();
                                        updateConversationStore(cID);
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
                        StringBuilder _outputBody = new StringBuilder("[");
                        synchronized(conversationsLock) {
                            for(JSONStructs.Conversation c : conversations.values()) {
                                if(c.hasUser(userName)) {
                                    String cs = c.sendToUser(userName);
                                    _outputBody.append(cs);
                                    _outputBody.append(",");
                                    _outputBody.append("\n");
                                }
                            }
                        }
                        if(_outputBody.length() > 1) {
                            _outputBody.setLength(_outputBody.length() - 2);  // remove last comma and line break
                        }
                        _outputBody.append("]");
                        outputBody = "conversation_ls;" + _outputBody.toString();
                    } else if(message.startsWith("conversation_cat ")) {
                        messageClasses = "command";
                        if(message.length() == 17) return true;
                        String[] fields = message.substring(17).split(" ");
                        int nBack = 100;
                        int cID = -1;
                        try {
                            cID = Integer.parseInt(fields[0]);
                        } catch(NumberFormatException e) {
                            return true;
                        }
                        if(fields.length >= 2) {
                            try {
                                nBack = Integer.parseInt(fields[1]);
                            } catch(NumberFormatException ignored) {
                            }
                        }
                        JSONStructs.Conversation c = null;
                        synchronized(conversationsLock) {
                            c = conversations.get(cID);
                            if(c == null || !c.hasUser(userName)) return true;
                        }
                        StringBuilder _outputBody = new StringBuilder();
                        synchronized(messagesLock) {
                            String[] cMessages = messageStore.get(cID).toArray(new String[0]);
                            if(cMessages.length > nBack)
                                cMessages = Arrays.copyOfRange(cMessages, cMessages.length - nBack, cMessages.length);
                            _outputBody.append(JsonStream.serialize(cMessages));
                        }
                        outputBody = "conversation_cat;" + _outputBody.toString();
                    } else if(message.startsWith("retrieve_keys_self")) {
                        messageClasses = "command";
                        String ks = MariaDBReader.retrieveKeysSelf(userName);
                        if(ks == null) {
                            return false;
                        }
                        outputBody = "retrieve_keys_self;" + ks;
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
                        StringBuilder _outputBody = new StringBuilder("[");
                        for(String u : otherUsers) {
                            String ks = MariaDBReader.retrieveKeysOther(u, userName);
                            if(ks == null) {
                                return true;
                            }
                            _outputBody.append(ks);
                            _outputBody.append(",");
                            _outputBody.append("\n");
                        }
                        if(_outputBody.length() > 1) {
                            _outputBody.setLength(_outputBody.length() - 2);  // remove last comma and line break
                        }
                        _outputBody.append("]");
                        outputBody = "retrieve_keys_other;" + _outputBody.toString();
                    } else if(message.startsWith("quit")) {
                        return false;
                    } else if(message.startsWith("format ")) {
                        messageClasses = "hide";
                        if(message.substring(7).equals("on")) {
                            markDown = true;
                            outputBody = "format on";
                        } else if(message.substring(7).equals("off")) {
                            markDown = false;
                            outputBody = "format off";
                        } else {
                            // invalid format command
                            return true;
                        }
                    } else {
                        // invalid server command
                        return true;
                    }

                    StringBuilder outMessage = new StringBuilder();
                    outMessage.append(conversationID).append(";");
                    outMessage.append(messageClasses).append(";");
                    outMessage.append(outputBody);
                    enqueue(outMessage.toString());
                } else {
                    JSONStructs.Conversation c = null;
                    synchronized(conversationsLock) {
                        c = conversations.get(conversationID);
                        if(c == null || !c.hasUser(userName)) return true;
                    }
                    messageClasses = "user " + (markDown ? "markdown" : "plaintext");
                    StringBuilder outMessage = new StringBuilder();
                    outMessage.append(conversationID).append(";");
                    outMessage.append(userName).append(";");
                    outMessage.append(System.currentTimeMillis()).append(";");
                    outMessage.append(messageClasses).append(";");
                    outMessage.append(cipherParams).append(";");
                    outMessage.append(hmac).append(";");
                    outMessage.append(message);
                    peerMessage.execute(this, outMessage.toString(), c.userNameList, conversationID);
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
                userNamesMap.remove(uuid);
            }

            @Override
            public void run() {
                wsSocket = wsServer.getSocketWhenAvailable(uuid);
                if(wsSocket == null) {
                    this.close();
                    return;
                }
                System.out.format("WebSocket connected @ %s\n", uuid);
                enqueue(first);
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
                long len = (header1[1] & 0x7F);
                boolean masked = ((header1[1] & 0x80) != 0);
                if(!masked) {
                    this.close();
                }

                if(len == 126) {
                    byte[] header2 = new byte[2];
                    int pos2 = 0;
                    while(pos2 < 2) {
                        pos2 += wsIn.read(header2, pos2, 2 - pos2);
                    }
                    len = 0;
                    for(int k = 0; k < 2; k++) {
                        len |= (header2[k] & 0xFF);
                        if(k < 1) len <<= 8;
                    }
                } else if(len == 127) {
                    byte[] header2 = new byte[8];
                    int pos2 = 0;
                    while(pos2 < 8) {
                        pos2 += wsIn.read(header2, pos2, 8 - pos2);
                    }
                    len = 0;
                    for(int k = 0; k < 8; k++) {
                        len |= (header2[k] & 0xFF);
                        if(k < 7) len <<= 8;
                    }
                }
                if(len > 0x7FFFFFFB) {  // len doesn't include mask
                    throw new RuntimeException("Message over 2GB");
                }
                byte[] data = new byte[(int)len + 4];
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
                    wsSocket.getOutputStream().write(WebSocketDataframe.toFrame(10, msg));
                    return "";
                } else if(opcode == 8) {
                    close();
                    return "";
                } else {
                    return "";
                }
            }

            private void enqueue(String outMessage) {
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


        Any.registerEncoders();
        JsonIterator.setMode(DecodingMode.REFLECTION_MODE);
        JsonStream.setMode(EncodingMode.REFLECTION_MODE);
        try(BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(conversationStorePath), UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while((line = in.readLine()) != null) {
                sb.append(line).append("\n");
            }
            Any convArray = JsonIterator.deserialize(sb.substring(0, sb.length() - 1));
            for(Any conv : convArray) {
                JSONStructs.Conversation c = new JSONStructs.Conversation();
                c = conv.bindTo(c);
                if(c.validate()) {
                    storeConversation(c.id, c, false);  // no one can connect yet, don't need lock
                }
            }
        } catch(IOException ignored) {
        }

        for(Integer cID : conversations.keySet()) {
            List<String> ml = new ArrayList<>();
            try(BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(
                    messageStorePathPrefix + cID + messageStorePathSuffix), UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while((line = in.readLine()) != null) {
                    sb.append(line).append("\n");
                }
                Any mArray = JsonIterator.deserialize(sb.substring(0, sb.length() - 1));
                ml = mArray.bindTo(ml);
            } catch(IOException ignored) {
            }
            messageStore.put(cID, ml);
        }
        System.out.format("%d conversation(s) loaded\n", conversations.size());

        final List<ClientThread> threads = new ArrayList<>();
        final peerUpdateCompat<ClientThread> messenger = new peerUpdateCompat<ClientThread>() {
            @Override
            public void execute(ClientThread caller, String message, List<String> recipients, Integer addToHistory) {
                if(addToHistory != null && addToHistory > 0) {
                    synchronized(messagesLock) {
                        List<String> ml = messageStore.get(addToHistory);
                        // Null messageStore entry should be caught by conversation check in handleMessage
                        ml.add(message);
                        updateMessageStore(addToHistory);
                    }
                }
                synchronized(threads) {
                    for(Iterator<ClientThread> threadIter = threads.iterator(); threadIter.hasNext(); /* nothing */) {
                        ClientThread currentThread = threadIter.next();
                        if(recipients.indexOf(currentThread.userName) != -1) {
                            if(!currentThread.isAlive()) {
                                threadIter.remove();
                                continue;
                            }
                            currentThread.enqueue(message);
                        }
                    }
                }
            }
        };

        int authPortNumber = 8081;
        InetSocketAddress bind = new InetSocketAddress(authPortNumber);
        System.out.println("Server @ " + bind.getAddress().getHostAddress() + ":" + bind.getPort());

        authServer = HttpServer.create(bind, 0);
        authServer.setExecutor(null);
        authServer.start();

        try {
            wsServer = new WebSocketServer("/root/0001.jks");
        } catch(Exception e) {
            throw new RuntimeException(e);
        }

        class DelegateHandler implements HttpHandler {
            @Override
            public void handle(HttpExchange conn) throws IOException {
                String uuid = null, userName = null, input;
                boolean join = false;
                boolean resume = false;
                try(BufferedReader in = new BufferedReader(new InputStreamReader(conn.getRequestBody(), UTF_8))
                ) {
                    input = in.readLine();
                    if(input.startsWith("join ")) {
                        join = true;
                        userName = input.substring(5);
                        if(!userName.matches("[0-9A-Za-z-_\\.]+")) {
                            join = false;
                            System.out.println("The trusted input has betrayed me");
                        }
                    }
                }

                // Allow file:// urls to request authorization if testing
                if("true".equals(System.getProperty("CNChat.testing"))) {
                    conn.getResponseHeaders().add("Access-Control-Allow-Origin", "null");
                }
                try(PrintWriter out = new PrintWriter(new OutputStreamWriter(conn.getResponseBody(), UTF_8), true)
                ) {
                    if(!join) {
                        conn.sendResponseHeaders(400, 2);
                        out.print("\r\n");
                        out.close();
                        return;
                    }

                    uuid = UUID.randomUUID().toString().replace("-", "");
                    synchronized(userNamesMapLock) {
                        resume = userNamesMap.containsKey(userName);
                        userNamesMap.put(userName, uuid);
                    }

                    if(resume) {
                        synchronized(threads) {
                            for(ClientThread currentThread : threads) {
                                if(userName.equals(currentThread.userName)) {
                                    currentThread.close();
                                    threads.remove(currentThread);
                                    break;
                                }
                            }
                        }
                    }

                    ClientThread thread = new ClientThread(uuid, userName, messenger);
                    synchronized(wsServer.authorizedLock) {
                        wsServer.authorized.add(uuid);
                    }
                    System.out.format("WebSocket waiting @ %s\n", thread.uuid);
                    synchronized(threads) {
                        threads.add(thread);
                    }
                    thread.start();

                    conn.sendResponseHeaders(200, 32);
                    out.print(uuid);
                }
            }
        }

        authServer.createContext("/", new DelegateHandler());
    }
}
