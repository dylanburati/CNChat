import ChatUtils.*;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.xml.bind.DatatypeConverter;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.*;

import static ChatUtils.AuthUtils.crypt64decode;
import static java.nio.charset.StandardCharsets.UTF_8;

interface peerUpdateCompat<T> {
    void execute(T thread, String message, List<String> specify, boolean addToHistory);
}

public class ChatServer {

    private static HttpServer authServer;
    private static volatile Map<String, String> userNamesMap = new HashMap<>();
    private static final Object userNamesMapLock = new Object();
    private static final String[] commandsAvailable = new String[] { "color ", "format ", "help", "status", "join ", "resume", "make persistent" };
    private static WebSocketServer wsServer;
    
    public static void main(String[] args) throws IOException {

        class ClientThread extends Thread {

            private String first = "Class:hide\nBody:connected";

            private final String uuid;
            private final peerUpdateCompat<ClientThread> peerMessage;
            private final String algo;
            String userName = null;
            private boolean markDown = false;
            private String userDataPath = null;

            boolean cryptHandlerLockReady = false;
            final Object cryptHandlerLock = new Object();
            private Cipher cipherE, cipherD;
            private byte[] privateKey;
            private Socket wsSocket = null;
            private final Object cipherLock = new Object();
            private final Object outStreamLock = new Object();
            private StringBuilder continuable = new StringBuilder();

            private ClientThread(String uuid, peerUpdateCompat<ClientThread> peerMessage, String algo) {
                this.uuid = uuid;
                this.peerMessage = peerMessage;
                this.algo = algo;
            }

            private boolean handleMessage(String message) {
                System.out.println("message received");
                System.out.println(message);
                if(message == null) {
                    // Decryption failed
                    return false;
                }

                String[] messageFull = message.split("\n");
                int headerLines;
                List<String> recipients = null;
                String command = "";

                for(headerLines = 0; headerLines < messageFull.length; headerLines++) {
                    if(messageFull[headerLines].startsWith("Recipients:")) {
                        String[] rs = messageFull[headerLines].substring(11).split(";");
                        recipients = new ArrayList<>();
                        for(String u : rs) {
                            if(!u.equals(userName) && u.matches("[0-9A-Za-z-_\\.]+")) recipients.add(u);
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
                boolean addToHistory = false;
                String messageClasses = "";
                String outputBody = "";
                List<String> moreHeaders = new ArrayList<>();

                if(command.isEmpty()) {
                    if(recipients == null) {
                        // User message without recipients specified
                        return true;
                    }
                    if(headerLines == messageFull.length) {
                        // User message without body
                        return true;
                    }
                    addToHistory = true;
                    messageClasses = "user " + (markDown ? "markdown" : "plaintext");
                    recipients.add(userName);
                    moreHeaders.add("From:" + userName);
                    int bodyStart = 0;
                    for(int i = 0; i < headerLines; i++) {
                        bodyStart += messageFull[i].length() + 1;
                    }
                    outputBody = message.substring(bodyStart + 5);
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
                    if(userName != null) {
                        enqueue("Class:hide\nBody:can't change username", false);
                    } else if(nameRequest.matches("[0-9A-Za-z-_\\.]+")) {
                        boolean conflict;
                        synchronized(userNamesMapLock) {
                            conflict = userNamesMap.containsValue(nameRequest);
                        }
                        if(conflict) {
                            enqueue("Class:hide\nBody:name conflict", false);
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
                } else if(command.startsWith("make persistent")) {
                    if(userDataPath == null) {
                        String passwordHashEnc = MariaDBReader.selectUsers(userName, "pass");
                        boolean correctEnc = true;
                        for(int i = 0; i < 3 && correctEnc; i++) {
                            switch(i) {
                                case 0:
                                    if(passwordHashEnc == null || !(passwordHashEnc.startsWith("$2y$")))
                                        correctEnc = false;
                                    else
                                        passwordHashEnc = passwordHashEnc.substring(4);
                                    break;
                                case 1:
                                    int endCostParam = passwordHashEnc.indexOf("$");
                                    if(endCostParam == -1 || endCostParam >= passwordHashEnc.length() - 1)
                                        correctEnc = false;
                                    else
                                        passwordHashEnc = passwordHashEnc.substring(endCostParam + 1);
                                    break;
                                case 2:
                                    if(passwordHashEnc.length() != 53)
                                        correctEnc = false;
                                    else
                                        passwordHashEnc = passwordHashEnc.substring(22);
                                    break;
                            }
                        }
                        if(correctEnc) {
                            userDataPath = new File(ChatServer.class.getProtectionDomain().getCodeSource().getLocation().getFile()).
                                    getParent() + System.getProperty("file.separator") + "." + uuid;
                            messageClasses = "hide";
                            recipients = new ArrayList<>();
                            recipients.add(userName);
                            int rMod = MariaDBReader.updateUserID(userName, uuid);
                            if(rMod != 1) {
                                outputBody = "failure";
                                userDataPath = null;
                            } else {
                                byte[] passwordHash = crypt64decode(passwordHashEnc);
                                byte[] currentIV;
                                synchronized(cipherLock) {
                                    currentIV = cipherE.getIV();
                                }
                                try(PrintWriter out = new PrintWriter(new OutputStreamWriter(new FileOutputStream(userDataPath), UTF_8), true)) {
                                    out.write("<key>");
                                    for(int i = 0; i < 16; i++) {
                                        int store = ((privateKey[i] & 0xFF) ^ (passwordHash[i] & 0xFF));
                                        out.write(String.format("%02x", store));
                                    }
                                    out.write("</key>\n");
                                    out.write("<iv>");
                                    for(int i = 0; i < 16; i++) {
                                        out.write(String.format("%02x", (currentIV[i] & 0xFF)));
                                    }
                                    out.write("</iv>\n");
                                } catch(IOException e) {
                                    e.printStackTrace();
                                    outputBody = "failure";
                                    userDataPath = null;
                                }
                            }
                            if(outputBody.isEmpty()) outputBody = "success";
                        } else {
                            messageClasses = "hide";
                            recipients = new ArrayList<>();
                            recipients.add(userName);
                            outputBody = "persistence not available";
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
                    for(String moreHeader : moreHeaders) {
                        outMessage.append("\n").append(moreHeader);
                    }
                    outMessage.append("\nBody:").append(outputBody);
                    peerMessage.execute(this, outMessage.toString(), recipients, addToHistory);
                }
                return true;
            }

            private void close() {
                cipherD = cipherE = null;
                if(wsSocket != null) {
                    try {
                        wsSocket.close();
                    } catch(IOException ignored) {
                    }
                }
                synchronized(wsServer.authorizedLock) {
                    wsServer.authorized.remove(uuid);
                }
                if(userDataPath == null && uuid != null) {
                    synchronized(userNamesMapLock) {
                        userNamesMap.remove(uuid);
                    }
                    System.out.format("Removed non-persistent: %s -> %s\n", uuid, userName);
                }
            }

            @Override
            public void run() {
                boolean resume = false;
                synchronized(userNamesMapLock) {
                    userName = userNamesMap.get(uuid);
                }
                if(userName != null) {
                    userDataPath = new File(ChatServer.class.getProtectionDomain().getCodeSource().getLocation().getFile()).
                            getParent() + System.getProperty("file.separator") + "." + uuid;
                    first += " and signed in";
                    resume = true;
                }
                try {
                    byte[] currentIV = null;
                    synchronized(cipherLock) {
                        cryptHandlerLockReady = true;
                        if(resume) {
                            ChatCryptResume chatCryptResume = new ChatCryptResume(authServer, uuid, userName, algo, userDataPath, cryptHandlerLock);
                            cipherD = chatCryptResume.cipherD;
                            cipherE = chatCryptResume.cipherE;
                            privateKey = chatCryptResume.privateKey;
                            currentIV = cipherE.getIV();
                        } else {
                            ChatCrypt chatCrypt = new ChatCrypt(authServer, uuid, algo, cryptHandlerLock);
                            cipherD = chatCrypt.cipherD;
                            cipherE = chatCrypt.cipherE;
                            privateKey = chatCrypt.privateKey;
                        }
                    }
                    if(resume && currentIV != null) {
                        try(PrintWriter out = new PrintWriter(new OutputStreamWriter(new FileOutputStream(userDataPath, true), UTF_8), true)) {
                            out.write("<iv>");
                            for(int i = 0; i < 16; i++) {
                                out.write(String.format("%02x", (currentIV[i] & 0xFF)));
                            }
                            out.write("</iv>\n");
                        } catch(IOException e) {
                            e.printStackTrace();
                        }
                    }
                } catch(Exception e) {
                    e.printStackTrace();
                }

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
                    while(!finished && cipherE != null && cipherD != null) {
                        try {
                            Thread.sleep(10);
                        } catch(InterruptedException ignored) {
                        }

                        String[] messagesEnc = getWSMessages().split("\n");
                        for(int i = 0; i < messagesEnc.length && !finished && cipherE != null && cipherD != null; i++) {
                            String message = decrypt(messagesEnc[i]);
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

            private String decrypt(String input) throws IOException {
                try {
                    byte[] data = DatatypeConverter.parseBase64Binary(input);
                    System.out.println(Arrays.toString(data));
                    synchronized(cipherLock) {
                        data = cipherD.doFinal(data);
                    }
                    return new String(data, UTF_8);
                } catch(IllegalBlockSizeException | BadPaddingException e) {
                    e.printStackTrace();
                    return null;
                }
            }

            private void enqueue(String outputLine, boolean addToHistory) {
                byte[] data = outputLine.getBytes(UTF_8);
                byte[] enc;
                String outEnc = null;
                try {
                    synchronized(cipherLock) {
                        enc = cipherE.doFinal(data);
                    }
                    outEnc = DatatypeConverter.printBase64Binary(enc);
                    byte[] wsOutEnc = WebSocketDataframe.toFrame(1, outEnc);
                    synchronized(outStreamLock) {
                        wsSocket.getOutputStream().write(wsOutEnc);
                    }
                } catch(IllegalBlockSizeException | BadPaddingException e) {
                    e.printStackTrace();
                } catch(IOException e) {
                    this.close();
                }
                if(addToHistory && userDataPath != null && outEnc != null) {
                    try(PrintWriter history = new PrintWriter(new OutputStreamWriter(new FileOutputStream(userDataPath, true), UTF_8), true)) {
                        history.write(outEnc);
                        history.write("\n");
                    } catch(IOException e) {
                        e.printStackTrace();
                    }
                }
            }

        }

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

        Map<String, String> persistent = MariaDBReader.selectAllUserIDs();
        if(persistent == null) {
            System.out.println("Warning: Persistent sessions are not available without a configured MariaDB database.");
        } else if(!persistent.isEmpty()) {
            synchronized(userNamesMapLock) {
                persistent.forEach((chatID, name) -> {
                    if(AuthUtils.isValidUUID(chatID)) {
                        userNamesMap.put(chatID, name);
                        System.out.format("Persistent: %s -> %s\n", chatID, name);
                    }
                });
            }
        }

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
                String uuid, input;
                boolean sane = false;
                boolean resume = false;
                try(BufferedReader in = new BufferedReader(new InputStreamReader(conn.getRequestBody(), UTF_8))
                ) {
                    input = in.readLine();
                    sane = AuthUtils.isValidUUID(input);
                }

                // Allow file:// urls to request authorization if testing
                if("true".equals(System.getProperty("CNChat.testing"))) {
                    conn.getResponseHeaders().add("Access-Control-Allow-Origin", "null");
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
                        synchronized(threads) {
                            for(ClientThread currentThread : threads) {
                                if(uuid.equals(currentThread.uuid)) {
                                    currentThread.close();
                                    threads.remove(currentThread);
                                    break;
                                }
                            }
                        }
                    } else {
                        uuid = UUID.randomUUID().toString().replace("-", "");
                    }

                    ClientThread thread = new ClientThread(uuid, messenger, "AES/CBC/PKCS5Padding");
                    synchronized(wsServer.authorizedLock) {
                        wsServer.authorized.add(uuid);
                    }
                    System.out.format("Client connected @ %s\n", thread.uuid);
                    synchronized(threads) {
                        threads.add(thread);
                    }
                    thread.start();

                    while(!thread.cryptHandlerLockReady) {
                        try {  // Allow cryptHandlerLock to be locked by the thread's ChatCrypt instance
                            Thread.sleep(10);
                        } catch(InterruptedException ignored) {
                        }
                    }

                    synchronized(thread.cryptHandlerLock) {
                        conn.sendResponseHeaders(200, 32);
                        out.print(uuid);
                        out.close();
                    }
                }
            }
        } // Remove unnecessary

        authServer.createContext("/", new DelegateHandler());
    }
}
