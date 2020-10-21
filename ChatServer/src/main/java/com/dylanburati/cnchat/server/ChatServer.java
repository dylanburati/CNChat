package com.dylanburati.cnchat.server;

import com.dylanburati.cnchat.server.ChatUtils.*;
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
    private final Config config;
    private final MariaDBReader database;

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
                storeWriter.submit(() -> database.updateMessageStore(addToHistory, message));
            }
            synchronized(threads) {
                for(Iterator<ClientThread> threadIter = threads.iterator(); threadIter.hasNext(); /* nothing */) {
                    ClientThread currentThread = threadIter.next();
                    if(recipients.contains(currentThread.getUserName())) {
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
                    database.updateConversationStore(toAdd);
                }
            }
        } catch(JsonException e) {
            e.printStackTrace();
            return null;
        }

        return toAdd;
    }

    public ChatServer(String preferenceStorePath) throws IOException {
        this.preferenceStorePath = preferenceStorePath;
        this.config = Config.loadFromEnv();
        new MariaDBSchemaManager(config).createTables();
        this.database = new MariaDBReader(config);

        conversations = database.getConversationStore();
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

        wsServer = new WebSocketServer(wsPortNumber);

        authServer.createContext("/", new DelegateHandler() {
            @Override
            public void onJoinRequest(String uuid, String userName) {
                ClientThread thread = new ClientThread(ChatServer.this, wsServer, uuid, userName, database);
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
