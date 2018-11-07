import ChatUtils.ChatCrypt;
import ChatUtils.ChatCryptResume;
import ChatUtils.TransactionHandler;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import java.io.*;
import java.net.InetSocketAddress;
import java.util.*;

import static ChatUtils.Codecs.base64decode;
import static ChatUtils.Codecs.base64encode;
import static java.nio.charset.StandardCharsets.UTF_8;

interface peerUpdateCompat<T> {
    void execute(T thread, String message, List<String> specify);
}

public class ChatServer {

    private static HttpServer server;
    private static volatile Map<String, String> userNamesMap = new HashMap<>();
    private static final Object userNamesMapLock = new Object();
    private static final String[] commandsAvailable = new String[] { "color ", "format ", "help", "status", "join ", "resume", "make persistent " };
    private static String persistentPath;

    private static boolean isValidUUID(String s) {
        if(s.length() != 32) return false;
        for(int i = 0; i < 32; i++) {
            if(Character.digit(s.codePointAt(i), 16) == -1) {
                return false;
            }
        }
        return true;
    }

    public static void main(String[] args) throws IOException {

        class ClientThread extends Thread {

            private String first = "Class:hide\nBody:connected";

            private final String uuid;
            private final peerUpdateCompat<ClientThread> peerMessage;
            private final String algo;
            private HttpContext httpContext = null;
            String userName = null;
            private boolean markDown = false;
            private String userDataPath = null;

            private Cipher cipherE, cipherD;
            private byte[] privateKey;
            private final Object cipherLock = new Object();
            private volatile List<String> outQueue = new ArrayList<>();
            private final Object outQueueLock = new Object();
            private volatile List<String> inQueue = new ArrayList<>();
            private final Object inQueueLock = new Object();

            private ClientThread(String uuid, peerUpdateCompat<ClientThread> peerMessage, String algo) {
                this.uuid = uuid;
                this.peerMessage = peerMessage;
                this.algo = algo;
            }

            private boolean handleMessage(String message) {
                System.out.println("message received");
                System.out.println(message);
                String[] messageFull = message.split("\n");
                int headerLines;
                List<String> recipients = null;
                String command = "";

                for(headerLines = 0; headerLines < messageFull.length; headerLines++) {
                    if(messageFull[headerLines].startsWith("Recipients:")) {
                        String[] rs = messageFull[headerLines].substring(11).split(";");
                        recipients = new ArrayList<>();
                        for(String u : rs) {
                            if(u.matches("[0-9A-Za-z-_\\.]+")) recipients.add(u);
                        }
                    } else if(messageFull[headerLines].startsWith("Command:")) {
                        String uCommand = messageFull[headerLines].substring(8);
                        for(String c : commandsAvailable) {
                            if(uCommand.startsWith(c)) {
                                command = uCommand;
                            }
                        }
                        if(command.isEmpty()) {
                            // Unrecognized command
                            return true;
                        }
                    } else if(messageFull[headerLines].startsWith("Body:")) {
                        break;
                    }
                }
                if(headerLines == 0) {
                    // Message has no header
                    return true;
                }

                if(userName == null && !command.startsWith("join ")) {
                    // Join needed for all other commands and messages
                    return true;
                }
                String messageClasses = "";
                String outputBody = "";

                if(command.isEmpty()) {
                    if(recipients == null) {
                        // User message without recipients specified
                        return true;
                    }
                    if(headerLines == messageFull.length) {
                        // User message without body
                        return true;
                    }
                    messageClasses = "user " + (markDown ? "markdown" : "plaintext");
                    recipients.add(userName);
                    StringBuilder outputBodyBuilder = new StringBuilder(messageFull[headerLines].substring(5));
                    for(int i = headerLines + 1; i < messageFull.length; i++) {
                        outputBodyBuilder.append(messageFull[i]);
                        if(i < (messageFull.length - 1)) outputBodyBuilder.append("\n");
                    }
                    outputBody = outputBodyBuilder.toString();
                } else if(command.startsWith("help")) {
                    messageClasses = "server";
                    recipients = new ArrayList<>();
                    recipients.add(userName);
                    outputBody = "Commands start with a colon (:)" +
                            "\n:status sends you key info\n";
                } else if(command.startsWith("status")) {
                    messageClasses = "server";
                    recipients = new ArrayList<>();
                    recipients.add(userName);
                    outputBody = "<< Status >>" +
                            "\nUsername: " + userName +
                            "\nFormat: " + (markDown ? "Markdown" : "plain text") +
                            "\n :format for Markdown" +
                            "\n :unformat for plain text";
                } else if(command.startsWith("join ")) {
                    messageClasses = "hide";
                    recipients = new ArrayList<>();
                    String nameRequest = command.substring(5);
                    if(nameRequest.matches("[0-9A-Za-z-_\\.]+")) {
                        if(userName != null) {
                            enqueue("Class:hide\nBody:can't change username");
                        } else if(userNamesMap.containsValue(nameRequest)) {
                            enqueue("Class:hide\nBody:name conflict");
                        } else {
                            userName = nameRequest;
                            synchronized(userNamesMapLock) {
                                userNamesMap.put(uuid, userName);
                            }
                            outputBody = "success";
                            recipients.add(userName);
                        }
                    } else {
                        // Illegal character in username
                        return false;
                    }
                } else if(command.startsWith("make persistent ")) {
                    String password_hash = command.substring(16);
                    if(userDataPath == null) {
                        if(isValidUUID(password_hash)) {
                            userDataPath = persistentPath.substring(0, persistentPath.lastIndexOf(System.getProperty("file.separator")) + 1) + "." + uuid;
                            // First 128 bits of SHA-256, store (private key ^ password_hash), forget password_hash
                            messageClasses = "hide";
                            recipients = new ArrayList<>();
                            recipients.add(userName);
                            try(PrintWriter out = new PrintWriter(new OutputStreamWriter(new FileOutputStream(persistentPath, true), UTF_8), true)) {
                                out.write(uuid);
                                out.write(" ");
                                out.write(userName);
                                out.write("\n");
                            } catch(IOException e) {
                                e.printStackTrace();
                                outputBody = "failure";
                            }
                            try(PrintWriter out = new PrintWriter(new OutputStreamWriter(new FileOutputStream(userDataPath), UTF_8), true)) {
                                out.write("Key:");
                                for(int i = 0; i < 32; i += 2) {
                                    int pad = Integer.parseInt(password_hash.substring(i, i + 2), 16);
                                    int store = ((privateKey[i / 2] & 0xFF) ^ pad);
                                    out.write(String.format("%02x", store));
                                }
                                out.write("\n");
                            } catch(IOException e) {
                                e.printStackTrace();
                                outputBody = "failure";
                            }
                            if(outputBody.isEmpty()) outputBody = "success";
                        } else {
                            outputBody = "invalid format";
                        }
                    } else {
                        messageClasses = "hide";
                        recipients = new ArrayList<>();
                        recipients.add(userName);
                        outputBody = "already persistent";
                    }
                } else if(command.startsWith("quit")) {
                    return false;
                } else if(command.startsWith("format ")) {
                    messageClasses = "hide";
                    recipients = new ArrayList<>();
                    recipients.add(userName);
                    if(command.substring(7).equals("on")) {
                        markDown = true;
                        outputBody = "format on";
                    } else if(command.substring(7).equals("off")) {
                        markDown = false;
                        outputBody = "format off";
                    } else {
                        // invalid format command
                        return true;
                    }
                } else if(command.startsWith("color ")) {
                    if(recipients == null) {
                        // User message without recipients specified
                        return true;
                    }
                    if(headerLines == messageFull.length) {
                        // User message without body
                        return true;
                    }
                    recipients.add(userName);
                    messageClasses = "hide";
                    outputBody = command;  // todo validate color
                }

                if(recipients != null && !recipients.isEmpty()) {
                    StringBuilder outMessage = new StringBuilder("Recipients:");
                    for(int i = 0; i < recipients.size(); i++) {
                        outMessage.append(recipients.get(i));
                        if(i < (recipients.size() - 1)) outMessage.append(";");
                    }
                    outMessage.append("\nClass:").append(messageClasses);
                    outMessage.append("\nBody:").append(outputBody);
                    peerMessage.execute(this, outMessage.toString(), recipients);
                }
                return true;
            }

            private void close() {
                cipherD = cipherE = null;
                server.removeContext(httpContext);
            }

            @Override
            public void run() {
                boolean resume = false;
                synchronized(userNamesMapLock) {
                    userName = userNamesMap.get(uuid);
                }
                if(userName != null) {
                    userDataPath = persistentPath.substring(0, persistentPath.lastIndexOf(System.getProperty("file.separator")) + 1) + "." + uuid;
                    first += " and signed in";
                    resume = true;
                }
                try {
                    synchronized(cipherLock) {
                        if(resume) {
                            ChatCryptResume chatCryptResume = new ChatCryptResume(server, uuid, algo, userDataPath);
                            cipherD = chatCryptResume.cipherD;
                            cipherE = chatCryptResume.cipherE;
                            privateKey = chatCryptResume.privateKey;
                        } else {
                            ChatCrypt chatCrypt = new ChatCrypt(server, uuid, algo);
                            cipherD = chatCrypt.cipherD;
                            cipherE = chatCrypt.cipherE;
                            privateKey = chatCrypt.privateKey;
                        }
                    }
                } catch(Exception e) {
                    e.printStackTrace();
                }
                enqueue(first);

                httpContext = server.createContext("/" + uuid, new TransactionHandler(inQueue, inQueueLock, outQueue, outQueueLock));
                try {
                    String inputLine;
                    boolean finished = false;
                    while(!finished) {
                        try {
                            Thread.sleep(10);
                        } catch(InterruptedException ignored) {
                        }

                        while(!finished) {
                            synchronized(inQueueLock) {
                                if(inQueue.size() > 0) inputLine = inQueue.remove(0);
                                else break;
                            }
                            String message = decrypt(inputLine);
                            synchronized(outQueueLock) {
                                finished = (handleMessage(message) == false);
                            }
                        }
                    }
                } catch(IOException e) {
                    e.printStackTrace();
                } finally {
                    close();
                }
            }

            private String decrypt(String input) throws IOException {
                try {
                    byte[] data = base64decode(input);
                    synchronized(cipherLock) {
                        data = cipherD.doFinal(data);
                    }
                    return new String(data, UTF_8);
                } catch(IllegalBlockSizeException | BadPaddingException e) {
                    e.printStackTrace();
                    return null;
                }
            }

            private void enqueue(String outputLine) {
                byte[] data = outputLine.getBytes(UTF_8);
                try {
                    byte[] enc;
                    synchronized(cipherLock) {
                        enc = cipherE.doFinal(data);
                    }
                    synchronized(outQueueLock) {
                        outQueue.add(base64encode(enc));
                    }
                } catch(IllegalBlockSizeException | BadPaddingException e) {
                    e.printStackTrace();
                }
            }

        }

        final List<ClientThread> threads = new ArrayList<>();
        final peerUpdateCompat<ClientThread> messenger = new peerUpdateCompat<ClientThread>() {
            @Override
            public void execute(ClientThread caller, String message, List<String> recipients) {
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

        persistentPath = new File(ChatServer.class.getProtectionDomain().getCodeSource().getLocation().getFile()).
                getParent() + System.getProperty("file.separator") + "persist.txt";
        try(BufferedReader persist = new BufferedReader(new InputStreamReader(new FileInputStream(persistentPath), UTF_8))) {
            String line;
            while((line = persist.readLine()) != null) {
                if(line.isEmpty()) {
                    break;
                }
                String lineUUID = line.substring(0, 32);
                String lineUser = line.substring(33);
                if(isValidUUID(lineUUID) && lineUser.matches("[0-9A-Za-z-_\\.]+")) {
                    System.out.println("PERSISTENT uuid: " + lineUUID + "| user: " + lineUser);
                    synchronized(userNamesMapLock) {
                        userNamesMap.put(lineUUID, lineUser);
                    }
                }
            }
        } catch(IOException ignored) {
        }

        int portNumber = 8081;
        InetSocketAddress bind = new InetSocketAddress(portNumber);
        System.out.println("Server @ " + bind.getAddress().getHostAddress() + ":" + bind.getPort());

        server = HttpServer.create(bind, 0);
        server.setExecutor(null);
        server.start();

        class DelegateHandler implements HttpHandler {
            @Override
            public void handle(HttpExchange conn) throws IOException {
                String uuid, input;
                boolean sane = false;
                boolean resume = false;
                try(BufferedReader in = new BufferedReader(new InputStreamReader(conn.getRequestBody(), UTF_8))
                ) {
                    input = in.readLine();
                    sane = isValidUUID(input);
                }

                try(PrintWriter out = new PrintWriter(new OutputStreamWriter(conn.getResponseBody(), UTF_8), true)
                ) {
                    if(!sane) {
                        conn.sendResponseHeaders(400, 2);
                        out.print("\r\n");
                        out.close();
                        return;
                    }

                    synchronized(userNamesMapLock) {
                        resume = userNamesMap.containsKey(input);
                    }

                    if(resume) {
                        uuid = input;
                    } else {
                        uuid = UUID.randomUUID().toString().replace("-", "");
                    }
                    conn.sendResponseHeaders(200, 32);
                    out.print(uuid);
                    out.close();
                }

                System.out.println("Client connected");
                ClientThread thread = new ClientThread(uuid, messenger, "AES/CBC/PKCS5Padding");
                synchronized(threads) {
                    threads.add(thread);
                }
                thread.start();
            }
        }

        server.createContext("/", new DelegateHandler());
    }
}
