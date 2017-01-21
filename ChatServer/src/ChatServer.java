import ChatUtils.ChatCrypt;
import ChatUtils.TransactionHandler;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static ChatUtils.Codecs.base64decode;
import static ChatUtils.Codecs.base64encode;
import static java.nio.charset.StandardCharsets.UTF_8;

interface peerUpdateCompat<T> {
    void execute(T thread, String message, String specify);
}

public class ChatServer {

    private static HttpServer server;
    private static boolean up = true;
    private static volatile List<String> userNames = new ArrayList<>();
    private static final Object userNamesLock = new Object();

    private static String stringJoin(String delimiter, Iterable<? extends String> elements) {
        StringBuilder retval = new StringBuilder();
        for(String el : elements) {
            retval.append(el);
            retval.append(delimiter);
        }
        return retval.toString();
    }

    public static void main(String[] args) throws IOException {

        class ClientThread extends Thread {

            private final String first = "\nWelcome to Cyber Naysh Chat\ntype ':help' for help\n\n"
                    + (!userNames.isEmpty() ? "<< Here now >>\n " + stringJoin("\n ", userNames) : "<< No one else is here >>")
                    + (char) 5 + (char) 17 + "\n";

            private final String uuid;
            private final peerUpdateCompat<ClientThread> peerMessage;
            private HttpContext httpContext = null;
            private String userName, dmUser = "";
            private boolean markDown = false;

            private Cipher cipherE, cipherD;
            private final Object cipherLock = new Object();
            private volatile List<String> outQueue = new ArrayList<>();
            private final Object outQueueLock = new Object();
            private volatile List<String> inQueue = new ArrayList<>();
            private final Object inQueueLock = new Object();

            private ClientThread(String uuid, peerUpdateCompat<ClientThread> peerMessage) {
                this.uuid = uuid;
                this.peerMessage = peerMessage;
            }

            private boolean handleMessage(String outputLine) {
                boolean finished = false;
                boolean messageAll = true;
                boolean messageMe = true;
                dmUser = "";
                if (outputLine.contains(":serverquit")) {
                    up = false;
                    outputLine = "<< " + userName + " ended the chat >>" + (char) 5;
                } else if (outputLine.contains(":help")) {
                    messageAll = false;
                    outputLine = "Commands start with a colon (:)" +
                            "\n:status sends you key info" +
                            "\n:dm <user> sends a direct message" +
                            "\n:username <new username>" +
                            "\n:quit closes your chat box" +
                            "\n:serverquit ends the chat" + (char) 5 + "\n";
                } else if (outputLine.contains(":status")) {
                    messageAll = false;
                    outputLine = "<< Status >>" +
                            "\nUsername: " + userName +
                            "\nFormat: " + (markDown ? "Markdown" : "plain text") +
                            "\n :format for Markdown" +
                            "\n :unformat for plain text" + (char) 5;
                    if(userNames.size() > 1) {
                        outputLine += "\nUsers here now:" + "\n";
                        for (String usr : userNames) {
                            if (!usr.equals(userName)) outputLine += " " + usr + "\n";
                        }
                    } else {
                        outputLine += "\nNo one else is here" + "\n";
                    }
                } else {
                    final int command = outputLine.isEmpty() ? -1 : outputLine.codePointAt(0);
                    if (command == 6) {
                        String nameRequest = outputLine.substring(1);
                        if (userNames.contains(nameRequest)) {
                            messageAll = false;
                            outputLine = "" + (char) 21;
                        } else {
                            messageMe = false;
                            userName = nameRequest;
                            synchronized (userNamesLock) {
                                userNames.add(userName);
                            }
                            outputLine = "<< " + userName + " joined the chat >>" + (char)5;
                        }
                    } else if (finished = (command == 4)) {
                        messageMe = false;
                        synchronized (userNamesLock) {
                            userNames.remove(userName);
                        }
                        outputLine = "<< " + outputLine + " left the chat >>" + (char)5;
                    } else if (command == 26) {
                        final int delimiter = outputLine.lastIndexOf(26);
                        String nameRequest = outputLine.substring(delimiter + 1);
                        if (userNames.contains(nameRequest)) {
                            messageAll = false;
                            outputLine = (char) 21 + outputLine.substring(1, delimiter);
                        } else {
                            messageMe = false;
                            synchronized (userNamesLock) {
                                userNames.remove(userName);
                                userNames.add(nameRequest);
                            }
                            outputLine = "<< " + userName + " is now " + nameRequest + " >>" + (char)5;
                            userName = nameRequest;
                        }
                    }
                    if (command == 15) {
                        final int rcvIndex = outputLine.indexOf(":dm ");
                        if (rcvIndex != -1 && dmUser.isEmpty()) {
                            if (!outputLine.contains("\n")) {
                                messageAll = false;
                                outputLine = "<< Can't send empty DM >>" + (char) 5;
                            } else {
                                int delimiter = outputLine.indexOf("\r");
                                if(delimiter == -1) delimiter = outputLine.indexOf("\n");
                                String dmRequest = outputLine.substring(rcvIndex + 4, delimiter);
                                if (userNames.contains(dmRequest)) {
                                    dmUser = dmRequest;
                                    outputLine = userName + ": << DM to " + dmUser + " >>" + outputLine.substring(delimiter);
                                } else {
                                    messageAll = false;
                                    outputLine = "<< User not found >>" + (char) 5;
                                }
                            }
                        }
                    }
                    if(command == 17) {
                        messageAll = messageMe = false;
                        markDown = outputLine.length() == 1;
                    }
                }
                if (!dmUser.isEmpty()) {
                    outputLine += (char) 15;
                }
                if(markDown) outputLine += (char)17;
                if (messageMe) enqueue(outputLine);
                if (messageAll) peerMessage.execute(this, outputLine, dmUser);
                if (!up) System.exit(0);
                return finished;
            }

            @Override
            public void run() {
                try {
                    synchronized(cipherLock) {
                        ChatCrypt chatCrypt = new ChatCrypt(server, uuid);
                        cipherD = chatCrypt.cipherD;
                        cipherE = chatCrypt.cipherE;
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
                enqueue(first);

                httpContext = server.createContext("/" + uuid, new TransactionHandler(inQueue, inQueueLock, outQueue, outQueueLock));
                try {
                    String inputLine;
                    while (up) {
                        try {
                            Thread.sleep(10);
                        } catch(InterruptedException ignored) {
                        }
                        if(inQueue.size() > 0) {
                            synchronized (inQueueLock) {
                                inputLine = inQueue.remove(0);
                            }
                            boolean finished = handleMessage(decrypt(inputLine));
                            if(finished) break;
                        }
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                } finally {
                    server.removeContext(httpContext);
                }
            }

            private String decrypt(String input) throws IOException {
                try {
                    byte[] data = base64decode(input);
                    synchronized(cipherLock) {
                        data = cipherD.doFinal(data);
                    }
                    return new String(data, UTF_8);
                } catch (IllegalBlockSizeException | BadPaddingException e) {
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
                } catch (IllegalBlockSizeException | BadPaddingException e) {
                    e.printStackTrace();
                }
            }

            String getUserName() {
                return userName;
            }

        }

        int portNumber = 8080;
        InetSocketAddress bind = new InetSocketAddress(portNumber);
        System.out.println("Server @ " + bind.getAddress().getHostAddress() + ":" + bind.getPort());

        server = HttpServer.create(bind, 0);
        server.setExecutor(null);
        server.start();

        final List<ClientThread> threads = new ArrayList<>();
        final peerUpdateCompat<ClientThread> messenger = new peerUpdateCompat<ClientThread>() {
            @Override
            public void execute(ClientThread skip, String message, String user) {
                boolean everyone = user.isEmpty();
                for (ClientThread currentThread : threads) {
                    if (everyone || user.equals(currentThread.getUserName())) {
                        if (!currentThread.equals(skip)) currentThread.enqueue(message);
                    }
                }
            }
        };

        class DelegateHandler implements HttpHandler {
            @Override
            public void handle(HttpExchange conn) throws IOException {
                String uuid;
                try(PrintWriter out = new PrintWriter(new OutputStreamWriter(conn.getResponseBody(), UTF_8), true)
                ) {
                    uuid = UUID.randomUUID().toString().replace("-", "");
                    conn.sendResponseHeaders(200, 32);
                    out.print(uuid);
                    out.close();
                }

                InetSocketAddress epoint = conn.getRemoteAddress();
                System.out.println("Client @ " + epoint.getAddress().getHostAddress() + ":" + epoint.getPort());
                ClientThread thread = new ClientThread(uuid, messenger);
                threads.add(thread);
                thread.start();
            }
        }
        server.createContext("/", new DelegateHandler());
    }
}
