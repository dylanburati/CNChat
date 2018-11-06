import ChatUtils.ChatCrypt;
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

class FlagData {
    public List<String> users;
    public int maxFlags;

    public FlagData(String userID, int maxFlags) {
        this.users = new ArrayList<>();
        users.add(userID);
        this.maxFlags = maxFlags;
    }
}

public class ChatServer {

    private static HttpServer server;
    private static volatile List<String> userNames = new ArrayList<>();
    private static final Object userNamesLock = new Object();
    private static volatile Map<String,FlagData> flaggedMessages = new HashMap<>();
    private static final Object flaggedMessagesLock = new Object();
    private static final String[] commandsAvailable = new String[] { "color ", "format ", "help", "status", "join ", "resume" };

    private static String usersHere() {
        StringBuilder retval = new StringBuilder();
        synchronized(userNamesLock) {
            for(String el : userNames) {
                retval.append(" ");
                retval.append(el);
                retval.append("\n");
            }
        }
        return retval.toString();
    }

    private static void listRemoveAll(List l) {
        //while(l != null && l.size() > 0) l.remove(0);
        l = new ArrayList<String>();
    }

    public static void main(String[] args) throws IOException {

        class ClientThread extends Thread {

            private final String first = "\nWelcome to Cyber Naysh Chat\ntype ':help' for help\n\n"
                    + (!userNames.isEmpty() ? "<< Here now >>\n" + usersHere() : "<< No one else is here >>\n")
                    + (char) 5 + (char) 17 + "\n";

            private final String uuid;
            private final peerUpdateCompat<ClientThread> peerMessage;
            private final String algo;
            private HttpContext httpContext = null;
            String userName = null;
            private boolean markDown = false;

            private Cipher cipherE, cipherD;
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
                    if(userNames.size() > 1) {
                        outputBody += "\nUsers here now:\n";
                        outputBody += usersHere();
                    } else {
                        outputBody += "\nNo one else is here" + "\n";
                    }
                } else if(command.startsWith("join ")) {
                    messageClasses = "hide";
                    recipients = new ArrayList<>();
                    String nameRequest = command.substring(5);
                    if(nameRequest.matches("[0-9A-Za-z-_\\.]+")) {
                        if(userNames.contains(nameRequest)) {
                            enqueue("Class:hide\nBody:name conflict");
                        } else {
                            userName = nameRequest;
                            synchronized(userNamesLock) {
                                userNames.add(userName);
                            }
                            outputBody = "success";
                            recipients.add(userName);
                        }
                    } else {
                        // Illegal character in username
                        return false;
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
                if(userName != null) {
                    synchronized(userNamesLock) {
                        userNames.remove(userName);
                    }
                }
                cipherD = cipherE = null;
                server.removeContext(httpContext);
            }

            @Override
            public void run() {
                try {
                    synchronized(cipherLock) {
                        ChatCrypt chatCrypt = new ChatCrypt(server, uuid, algo);
                        cipherD = chatCrypt.cipherD;
                        cipherE = chatCrypt.cipherE;
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
                            finished = (handleMessage(decrypt(inputLine)) == false);
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

        int portNumber = 8080;
        InetSocketAddress bind = new InetSocketAddress(portNumber);
        System.out.println("Server @ " + bind.getAddress().getHostAddress() + ":" + bind.getPort());

        server = HttpServer.create(bind, 0);
        server.setExecutor(null);
        server.start();

        class DelegateHandler implements HttpHandler {
            @Override
            public void handle(HttpExchange conn) throws IOException {
                String uuid, algo;
                int cipherModeFlag;
                try(BufferedReader in = new BufferedReader(new InputStreamReader(conn.getRequestBody(), UTF_8))
                ) {
                    String input = in.readLine();
                    cipherModeFlag = processRootRequest(input);
                }

                try(PrintWriter out = new PrintWriter(new OutputStreamWriter(conn.getResponseBody(), UTF_8), true)
                ) {
                    if(cipherModeFlag == -1) {
                        conn.sendResponseHeaders(400, 2);
                        out.print("\r\n");
                        out.close();
                        return;
                    }

                    uuid = UUID.randomUUID().toString().replace("-", "");
                    conn.sendResponseHeaders(200, 32);
                    out.print(uuid);
                    out.close();
                }

                System.out.println("Client connected");
                algo = cipherModeFlag > 7 ? "AES/CBC/PKCS5Padding" : "AES/CTR/PKCS5Padding";
                ClientThread thread = new ClientThread(uuid, messenger, algo);
                synchronized(threads) {
                    threads.add(thread);
                }
                thread.start();
            }

            private int processRootRequest(String input) {
                if(input == null || input.length() != 32) {
                    return -1;
                }
                int cipherModeFlag = Character.digit(input.codePointAt(0), 16);
                if(cipherModeFlag == -1) {
                    return -1;
                }
                for(int i = 1; i < 32; i++) {
                    if(Character.digit(input.codePointAt(i), 16) == -1) {
                        return -1;
                    }
                }
                return cipherModeFlag;
            }
        }

        server.createContext("/", new DelegateHandler());
    }
}
