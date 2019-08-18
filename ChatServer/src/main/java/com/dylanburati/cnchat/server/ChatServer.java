package com.dylanburati.cnchat.server;

import com.dylanburati.cnchat.server.ChatUtils.DelegateHandler;
import com.dylanburati.cnchat.server.ChatUtils.JSONStructs;
import com.dylanburati.cnchat.server.ChatUtils.MariaDBReader;
import com.dylanburati.cnchat.server.ChatUtils.WebSocketServer;
import com.jsoniter.JsonIterator;
import com.jsoniter.any.Any;
import com.jsoniter.output.EncodingMode;
import com.jsoniter.output.JsonStream;
import com.jsoniter.spi.DecodingMode;
import com.jsoniter.spi.JsonException;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static java.nio.charset.StandardCharsets.UTF_8;

public class ChatServer {
    interface OnlineMessagePasser {
        void execute(ClientThread thread, String message, List<String> specify, Integer addToHistory);
    }

    // Config options
    // "authPort": int
    private int authPortNumber = 8081;
    // "websocketPort": int
    private int wsPortNumber = 8082;
    // "keyStoreLocation": /absolute/path/to/file
    private String keyStoreLocation = null;

    // options stored in com.dylanburati.cnchat.MariaDBReader
    // "mariaDBPort": int, default=3306
    // "mariaDBUser": username
    // "mariaDBPassword": password
    // "database": db name
    // "tableForMessages": table name, default="$cnchat_messages"
    // "tableForConversations": table name, default="$cnchat_conversations"
    // "tableForConversationsUsers": table name, default="$cnchat_conversations_users"

    private HttpServer authServer;
    private WebSocketServer wsServer;
    public volatile Map<Integer, JSONStructs.Conversation> conversations = null;
    public final Object conversationsLock = new Object();

    private final Map<String, JSONStructs.Preferences> allPreferences = new HashMap<>();
    private final Object preferencesLock = new Object();
    private String preferenceStorePath;
    private final ExecutorService storeWriter = Executors.newSingleThreadExecutor();

    private final List<ClientThread> threads = new ArrayList<>();
    public final OnlineMessagePasser messenger = new OnlineMessagePasser() {
        @Override
        public void execute(ClientThread caller, String message, List<String> recipients, Integer addToHistory) {
            if(addToHistory != null && addToHistory > 0) {
                storeWriter.submit(() -> MariaDBReader.updateMessageStore(addToHistory, message));
            }
            synchronized(threads) {
                for(Iterator<ClientThread> threadIter = threads.iterator(); threadIter.hasNext(); /* nothing */) {
                    ClientThread currentThread = threadIter.next();
                    if(recipients.indexOf(currentThread.getUserName()) != -1) {
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

    public JSONStructs.Preferences getStoredPreferences(String user) {
        return allPreferences.get(user);
    }

    public void updatePreferenceStore(String user, JSONStructs.Preferences prefs) {
        synchronized(preferencesLock) {
            allPreferences.put(user, prefs);
            String toWrite = JsonStream.serialize(allPreferences);
            if (toWrite.length() > 0) {
                storeWriter.submit(() -> {
                    try (PrintWriter history = new PrintWriter(new OutputStreamWriter(new FileOutputStream(
                            preferenceStorePath), UTF_8), true)) {
                        history.write(toWrite);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                });
            }
        }
    }

    public boolean tryRequestConversation(String userName1, List<String> otherUsers) {
        boolean collision = false;
        HashSet<String> requestedUsers = new HashSet<>();
        requestedUsers.add(userName1);
        requestedUsers.addAll(otherUsers);

        return !checkUserListCollision(requestedUsers);
    }

    private boolean checkUserListCollision(Set<String> list) {
        boolean collision = false;
        synchronized(conversationsLock) {
            for(JSONStructs.Conversation existing : conversations.values()) {
                collision = existing.users.size() == list.size() && list.containsAll(existing.userNameList);
                if(collision) {
                    break;
                }
            }
        }

        return collision;
    }

    public String listConversationsForUser(String userName) {
        StringBuilder responseBuilder = new StringBuilder("[");
        List<String> conversationJsonList = new ArrayList<>();
        synchronized(conversationsLock) {
            for(JSONStructs.Conversation c : conversations.values()) {
                if(c.hasUser(userName)) {
                    String conversationJson = c.sendToUser(userName);
                    conversationJsonList.add(conversationJson);
                }
            }
        }
        return "[" + String.join(",\n", conversationJsonList) + "]";
    }

    public JSONStructs.Conversation tryAddConversation(String userName1, String conversationJson) {
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
                if(toAdd.validateNew(userName1)) {
                    if(checkUserListCollision(new HashSet<>(toAdd.userNameList))) {
                        return null;
                    }

                    conversations.put(toAdd.id, toAdd);
                    MariaDBReader.updateConversationStore(toAdd);
                }
            }
        } catch(JsonException e) {
            e.printStackTrace();
            return null;
        }

        return toAdd;
    }

    public ChatServer(String configPath, String preferenceStorePath) throws IOException {
        this.preferenceStorePath = preferenceStorePath;

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
                        case "tableForKeys":
                            MariaDBReader.keystoreTableName = iter.value().toString();
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

        authServer.createContext("/", new DelegateHandler() {
            @Override
            public void onJoinRequest(String uuid, String userName) {
                ClientThread thread = new ClientThread(ChatServer.this, wsServer, uuid, userName);
                synchronized(wsServer.authorizedLock) {
                    wsServer.authorized.add(uuid);
                }
                System.out.format("WebSocket waiting @ %s\n", uuid);
                synchronized(threads) {
                    threads.add(thread);
                }
                thread.start();
            }
        });
    }
}
