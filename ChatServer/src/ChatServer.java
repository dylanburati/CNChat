import ChatUtils.*;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import javax.xml.bind.DatatypeConverter;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.*;

import static java.nio.charset.StandardCharsets.UTF_8;

interface peerUpdateCompat<T> {
    void execute(T thread, String message, List<String> specify, boolean addToHistory);
}

public class ChatServer {

    private static HttpServer authServer;
    private static WebSocketServer wsServer;
    private static CNConversationStore conversations = new CNConversationStore(null);
    private static volatile Map<String, String> userNamesMap = new HashMap<>();
    private static final Object userNamesMapLock = new Object();
    private static final String[] commandsAvailable = new String[] { "color ", "format ", "help", "status", "_conversation_request " };

    public static void main(String[] args) throws IOException {

        class ClientThread extends Thread {

            private String first = "Class:hide\nBody:connected";

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
                System.out.println("message received");
                System.out.println(message);
                int conversationID = -1;
                String messageAuthenticityCode = null;  // todo
                try {
                    int f1 = message.indexOf(";");
                    conversationID = Integer.parseInt(message.substring(0, f1));
                    int f2 = message.indexOf(";", f1 + 1);
                    messageAuthenticityCode = message.substring(f1 + 1, f2);
                    message = message.substring(f2 + 1);
                } catch(NumberFormatException | IndexOutOfBoundsException e) {
                    return true;
                }
                if(conversationID < 0) {
                    return true;
                } else if(conversationID == 0) {
                    String messageClasses;
                    String outputBody;
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
                        messageClasses = "hide";
                        if(message.length() == 21) return true;
                        List<String> otherUsers = Arrays.asList(message.substring(21).split(";"));
                        StringBuilder _outputBody = new StringBuilder();
                        _outputBody.append(MariaDBReader.retrieveKeys(userName, true));
                        for(String u : otherUsers) {
                            String ks = MariaDBReader.retrieveKeys(u, false);
                            if(ks != null) {
                                _outputBody.append("\n");
                                _outputBody.append(ks);
                            }
                        }
                        outputBody = _outputBody.toString();
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

                    enqueue(outputBody, false);
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
                System.out.println("Waiting for WebSocket");
                wsSocket = wsServer.getSocketWhenAvailable(uuid);
                if(wsSocket == null) {
                    this.close();
                    return;
                }
                System.out.println("WebSocket obtained");
                enqueue(first, false);
                try {
                    boolean finished = false;
                    while(!finished) {
                        try {
                            Thread.sleep(10);
                        } catch(InterruptedException ignored) {
                        }

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

                System.out.println("reading");
                byte[] header1 = new byte[2];  // opcode, first length
                int pos1 = 0;
                while(pos1 < 2) {
                    pos1 += wsSocket.getInputStream().read(header1, pos1, 2 - pos1);
                }
                System.out.println("header1: " + Arrays.toString(header1));

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
                    System.out.println("header2: " + Arrays.toString(header2));
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
                    System.out.println("header2: " + Arrays.toString(header2));
                    len = 0;
                    for(int k = 0; k < 8; k++) {
                        len |= (header2[k] & 0xFF);
                        if(k < 7) len <<= 8;
                    }
                }
                if(len > 0x7FFFFFFB) {  // len doesn't include mask
                    throw new RuntimeException("Message over 2GB");
                }
                // System.out.println("len: " + len);
                byte[] data = new byte[(int)len + 4];
                int pos = 0;
                while(pos < data.length) {
                    pos += wsIn.read(data, pos, data.length - pos);
                }
                // System.out.println("data: " + Arrays.toString(data));
                String msg = WebSocketDataframe.getText(data);
                System.out.println(msg);
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

            private void enqueue(String outputLine, boolean addToHistory) {
                byte[] data = outputLine.getBytes(UTF_8);
                String outEnc = DatatypeConverter.printBase64Binary(data);
                byte[] wsOutEnc = WebSocketDataframe.toFrame(1, outEnc);
                try {
                    synchronized(outStreamLock) {
                        wsSocket.getOutputStream().write(wsOutEnc);
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
//                if(addToHistory && userDataPath != null && outEnc != null) {
//                    try(PrintWriter history = new PrintWriter(new OutputStreamWriter(new FileOutputStream(userDataPath, true), UTF_8), true)) {
//                        history.write(outEnc);
//                        history.write("\n");
//                    } catch(IOException e) {
//                        e.printStackTrace();
//                    }
//                }
            }
        } // end class ClientThread

        final List<ClientThread> threads = new ArrayList<>();
        final peerUpdateCompat<ClientThread> messenger = new peerUpdateCompat<ClientThread>() {
            @Override
            public void execute(ClientThread caller, String message, List<String> recipients, boolean addToHistory) {
                synchronized(threads) {
                    for(Iterator<ClientThread> threadIter = threads.iterator(); threadIter.hasNext(); /* nothing */) {
                        ClientThread currentThread = threadIter.next();
                        if(recipients.indexOf(currentThread.userName) != -1) {
                            if(!currentThread.isAlive()) {
                                threadIter.remove();
                                continue;
                            }
                            currentThread.enqueue(message, addToHistory);
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
                    System.out.format("Client connected @ %s\n", thread.uuid);
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
