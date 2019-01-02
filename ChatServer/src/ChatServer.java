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

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static java.nio.charset.StandardCharsets.UTF_8;

interface peerUpdateCompat<T> {
    void execute(T thread, String message, List<String> specify, Integer addToHistory);
}

public class ChatServer {

    // Config options
    // "authPort": int
    private static int authPortNumber = 8081;
    // "websocketPort": int
    private static int wsPortNumber = 8082;
    // "keyStoreLocation": /absolute/path/to/file
    private static String keyStoreLocation = null;

    // options stored in ChatUtils.MariaDBReader
    // "mariaDBPort": int, default=3306
    // "mariaDBUser": username
    // "mariaDBPassword": password
    // "database": db name
    // "tableForMessages": table name, default="$cnchat_messages"
    // "tableForConversations": table name, default="$cnchat_conversations"
    // "tableForConversationsUsers": table name, default="$cnchat_conversations_users"

    private static HttpServer authServer;
    private static WebSocketServer wsServer;
    private static volatile Map<Integer, JSONStructs.Conversation> conversations = null;
    private static final Object conversationsLock = new Object();

    private static final String workingDirectory = new File(ChatServer.class.getProtectionDomain().getCodeSource().getLocation().getFile()).
            getParent() + System.getProperty("file.separator");
    private static volatile Map<String, JSONStructs.Preferences> allPreferences = new HashMap<>();
    private static final Object preferencesLock = new Object();
    private static final String preferenceStorePath = workingDirectory + "user_preferences.json";
    private static ExecutorService storeWriter = Executors.newSingleThreadExecutor();

    private static volatile Map<String, String> userNamesMap = new HashMap<>();
    private static final Object userNamesMapLock = new Object();

    private static void updatePreferenceStore(String user, JSONStructs.Preferences prefs) {
        allPreferences.put(user, prefs);
        String toWrite = JsonStream.serialize(allPreferences);
        if(toWrite.length() > 0) {
            storeWriter.submit(() -> {
                try(PrintWriter history = new PrintWriter(new OutputStreamWriter(new FileOutputStream(
                        preferenceStorePath), UTF_8), true)) {
                    history.write(toWrite);
                } catch(IOException e) {
                    e.printStackTrace();
                }
            });
        }
    }

    public static void main(String[] args) throws IOException {

        class ClientThread extends Thread {

        private String first = "0;server;Connected";

            private final String uuid;
            private final peerUpdateCompat<ClientThread> peerMessage;
            String userName;
            private JSONStructs.Preferences userPreferences = new JSONStructs.Preferences();

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
                        boolean collision = false;
                        synchronized(conversationsLock) {
                            for(JSONStructs.Conversation existing : conversations.values()) {
                                collision = existing.users.size() == (otherUsers.size() + 1);
                                if(collision) {
                                    for(String existingUser : existing.userNameList) {
                                        if(!userName.equals(existingUser) &&
                                                otherUsers.indexOf(existingUser) == -1) {
                                            collision = false;
                                            // This means that at least 1 of the users in `existing` is not part of this conversation
                                            break;  // inner loop
                                        }
                                    }
                                    // if inner loop finishes, collision=true and the requested conversation already exists
                                    if(collision) {
                                        break;  // outer loop
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
                        JSONStructs.Conversation toAdd = new JSONStructs.Conversation();
                        try {
                            Any conv = JsonIterator.deserialize(conversationJson);

                            toAdd = conv.bindTo(toAdd);
                            toAdd.exchange_complete = false;
                            toAdd.crypt_expiration = System.currentTimeMillis() + (14 * 24 * 3600 * 1000);
                            synchronized(conversationsLock) {
                                if(conversations.size() == 0) {
                                    toAdd.id = 1;
                                } else {
                                    toAdd.id = Collections.max(conversations.keySet()) + 1;
                                }
                                if(toAdd.validateNew(userName)) {
                                    boolean collision = false;
                                    for(JSONStructs.Conversation existing : conversations.values()) {
                                        collision = existing.users.size() == (toAdd.userNameList.size());
                                        for(String existingUser : existing.userNameList) {
                                            if(toAdd.userNameList.indexOf(existingUser) == -1) {
                                                collision = false;
                                                // This means that at least 1 of the users in `existing` is not part of this conversation
                                                break;  // inner loop
                                            }
                                        }
                                        // if inner loop finishes, collision=true and the requested conversation already exists
                                        if(collision) {
                                            return true;
                                        }
                                    }

                                    conversations.put(toAdd.id, toAdd);
                                    MariaDBReader.updateConversationStore(toAdd);
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
                            String[] keyFields = fields[1].split(";");
                            if(keyFields.length != 3) return true;
                            for(int i = 0; i < 3; i++) {
                                switch(i) {
                                    case 0:
                                        // IV is 16 bytes -> 22 base64 chars
                                        if(!keyFields[i].matches("[0-9A-Za-z+/]{22}[=]{0,2}")) {
                                            return true;
                                        }
                                        break;
                                    case 1:
                                        // HMAC is exactly 32 bytes -> 43 base64 chars
                                        if(!keyFields[i].matches("[0-9A-Za-z+/]{43}[=]{0,1}")) {
                                            return true;
                                        }
                                        break;
                                    case 2:
                                        // Wrapped key can be 32 or 48 bytes (multiple of AES block size)
                                        // 43 base64 chars or 64
                                        if(!keyFields[i].matches("[0-9A-Za-z+/]{43}[=]{0,1}") &&
                                                !keyFields[i].matches("[0-9A-Za-z+/]{64}")) {
                                            return true;
                                        }
                                        break;
                                }
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
                        List<String> cMessages = MariaDBReader.getMessages(cID, nBack);
                        if(cMessages == null) {
                            return true;
                        }
                        _outputBody.append(JsonStream.serialize(cMessages));
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
                        synchronized(preferencesLock) {
                            updatePreferenceStore(userName, userPreferences);
                        }
                        outputBody = "set_preferences;" + JsonStream.serialize(userPreferences);
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
                    synchronized(conversationsLock) {
                        c = conversations.get(conversationID);
                        if(c == null || !c.hasUser(userName)) return true;
                    }
                    if(contentType.isEmpty()) {
                        messageClasses = "user " + (userPreferences.markdown ? "markdown" : "plaintext");
                    } else {
                        messageClasses = "user " + contentType;
                    }
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
                    synchronized(wsServer.authorizedLock) {
                        wsServer.authorized.remove(uuid);
                    }
                    return;
                }

                System.out.format("WebSocket connected @ %s\n", uuid);
                enqueue(first);
                synchronized(preferencesLock) {
                    JSONStructs.Preferences toUpdate = allPreferences.get(userName);
                    if(toUpdate == null) {
                        updatePreferenceStore(userName, this.userPreferences);
                    } else {
                        this.userPreferences.assign(toUpdate);
                    }
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

        final String configPath = workingDirectory + "config.json";
        Any.registerEncoders();
        JsonIterator.setMode(DecodingMode.REFLECTION_MODE);
        JsonStream.setMode(EncodingMode.REFLECTION_MODE);
        try(BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(configPath), UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while((line = in.readLine()) != null) {
                sb.append(line).append("\n");
            }
            if(sb.length() > 1) {
                Any allSettings = JsonIterator.deserialize(sb.substring(0, sb.length() - 1));
                Any.EntryIterator iter = allSettings.entries();
                while(iter.next()) {
                    String key = iter.key();
                    switch(key) {
                        case "authPort":
                            authPortNumber = iter.value().toInt();
                            break;
                        case "websocketPort":
                            wsPortNumber = iter.value().toInt();
                            break;
                        case "keyStoreLocation":
                            keyStoreLocation = iter.value().toString();
                            break;
                        case "mariaDBPort":
                            MariaDBReader.databasePort = iter.value().toInt();
                            break;
                        case "mariaDBUser":
                            MariaDBReader.databaseUser = iter.value().toString();
                            break;
                        case "mariaDBPassword":
                            MariaDBReader.databasePassword = iter.value().toString();
                            break;
                        case "database":
                            MariaDBReader.databaseName = iter.value().toString();
                            break;
                        case "tableForMessages":
                            MariaDBReader.messagesTableName = iter.value().toString();
                            break;
                        case "tableForConversations":
                            MariaDBReader.conversationsTableName = iter.value().toString();
                            break;
                        case "tableForConversationsUsers":
                            MariaDBReader.conversationsUsersTableName = iter.value().toString();
                            break;
                        default:
                            System.out.format("Warning: unknown key in config.json: '%s'\n", key);
                            break;
                    }
                }
            }
            if(authPortNumber <= 0 || authPortNumber > 65535 ||
                    wsPortNumber <= 0 || wsPortNumber > 65535 ||
                    keyStoreLocation == null ||
                    MariaDBReader.databasePort <= 0 || MariaDBReader.databasePort > 65535 ||
                    MariaDBReader.databaseUser == null ||
                    MariaDBReader.databasePassword == null ||
                    MariaDBReader.databaseName == null || MariaDBReader.databaseName.isEmpty() ||
                    MariaDBReader.messagesTableName == null || MariaDBReader.messagesTableName.isEmpty() ||
                    MariaDBReader.conversationsTableName == null || MariaDBReader.conversationsTableName.isEmpty() ||
                    MariaDBReader.conversationsUsersTableName == null || MariaDBReader.conversationsUsersTableName.isEmpty()) {
                throw new RuntimeException("Illegal value in configuration file");
            }
        } catch(IOException e) {
            throw new RuntimeException("Failed to load configuration file");
        }
        // All variables set by the config are final after this point

        conversations = MariaDBReader.getConversationStore();
        if(conversations == null) throw new RuntimeException("Failed to load conversations");

        try(BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(preferenceStorePath), UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while((line = in.readLine()) != null) {
                sb.append(line).append("\n");
            }
            if(sb.length() > 1) {
                Any allPrefs = JsonIterator.deserialize(sb.substring(0, sb.length() - 1));
                Any.EntryIterator iter = allPrefs.entries();
                while(iter.next()) {
                    JSONStructs.Preferences p = new JSONStructs.Preferences();
                    p = iter.value().bindTo(p);
                    allPreferences.put(iter.key(), p);
                }
            }
        } catch(IOException ignored) {
        }
        System.out.format("%d conversation(s) loaded\n", conversations.size());

        final List<ClientThread> threads = new ArrayList<>();
        final peerUpdateCompat<ClientThread> messenger = new peerUpdateCompat<ClientThread>() {
            @Override
            public void execute(ClientThread caller, String message, List<String> recipients, Integer addToHistory) {
                if(addToHistory != null && addToHistory > 0) {
                    storeWriter.submit(() -> MariaDBReader.updateMessageStore(addToHistory, message));
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

        InetSocketAddress bind = new InetSocketAddress(authPortNumber);
        System.out.println("Server @ " + bind.getAddress().getHostAddress() + ":" + bind.getPort());

        authServer = HttpServer.create(bind, 0);
        authServer.setExecutor(null);
        authServer.start();

        try {
            wsServer = new WebSocketServer(wsPortNumber, keyStoreLocation);
        } catch(Exception e) {
            throw new RuntimeException(e);
        }

        class DelegateHandler implements HttpHandler {
            @Override
            public void handle(HttpExchange conn) throws IOException {
                String uuid = null, userName = null, input;
                boolean join = false;
                try(BufferedReader in = new BufferedReader(new InputStreamReader(conn.getRequestBody(), UTF_8))
                ) {
                    input = in.readLine();
                    if(input != null && input.startsWith("join ")) {
                        join = true;
                        userName = input.substring(5);
                        if(!userName.matches("[0-9A-Za-z-_\\.]+")) {
                            join = false;
                            System.out.println("Username has illegal characters\n" +
                                    "Please make sure your auth server port (defaults to 8081) is not open to the Internet");
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
