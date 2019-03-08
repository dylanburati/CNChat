package ChatUtils;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.xml.bind.DatatypeConverter;
import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static java.nio.charset.StandardCharsets.UTF_8;

public class WebSocketServer {
    private ServerSocket serverSocket = null;
    private final Map<String, Socket> pendingConnections = new HashMap<>();
    private final Object pendingConnectionsLock = new Object();
    public final List<String> authorized = new ArrayList<>();
    public final Object authorizedLock = new Object();

    private final Lock availLock = new ReentrantLock();
    private final Condition availCheck = availLock.newCondition();

    private boolean upgradeFromHttp(Socket socket) {
        BufferedReader in = null;
        PrintWriter out = null;
        boolean upgrade = true;
        boolean complete = false;
        try {
            StringBuilder inputB = new StringBuilder();
            in = new BufferedReader(new InputStreamReader(socket.getInputStream(), UTF_8));
            String inLine;
            while((inLine = in.readLine()) != null) {
                if(inLine.isEmpty()) break;
                inputB.append(inLine).append("\n");
            }
            String input = "";
            if(inputB.length() > 0) {
                input = inputB.substring(0, inputB.length() - 1);
            }

            String uuid = null;
            String wsKey = null;
            for(int i = 0; i < 4 && upgrade; i++) {
                switch(i) {
                    case 0:
                        if(!input.startsWith("GET ")) {
                            upgrade = false;
                        }
                        break;
                    case 1:
                        int secondSpace = input.indexOf(" ", 4);
                        if(secondSpace == -1) {
                            upgrade = false;
                        }
                        String path = input.substring(4, secondSpace);
                        if(path.length() == 33 && path.startsWith("/")) {
                            uuid = path.substring(1);
                        }
                        if(!AuthUtils.isValidUUID(uuid)) {
                            upgrade = false;
                        }
                        if(upgrade) {
                            synchronized(authorizedLock) {
                                upgrade = authorized.remove(uuid);
                            }
                        }
                        break;
                    case 2:
                        int wsKeyIdx = input.indexOf("Sec-WebSocket-Key: ");
                        if(wsKeyIdx == -1 || wsKeyIdx >= (input.length() - 19)) {
                            upgrade = false;
                        } else {
                            input = input.substring(wsKeyIdx + 19);
                        }
                        break;
                    case 3:
                        int endKeyIdx = input.indexOf("\n");
                        if(endKeyIdx == -1) {
                            upgrade = false;
                        } else {
                            wsKey = input.substring(0, endKeyIdx);
                        }
                        break;
                }
            }

            String output = "";
            if(!upgrade || wsKey == null) {
                output = "HTTP/1.1 400 Bad Request\r\n" +
                        "Content-Length: 2\r\n" +
                        "\r\n" +
                        "\r\n";
            } else {
                output = "HTTP/1.1 101 Switching Protocols\r\n" +
                        "Upgrade: websocket\r\n" +
                        "Connection: Upgrade\r\n" +
                        "Sec-WebSocket-Accept: ";
                byte[] wsKeyEnc = MessageDigest.getInstance("SHA-1").digest(
                        (wsKey + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(UTF_8));
                output += DatatypeConverter.printBase64Binary(wsKeyEnc);
                output += "\r\n\r\n";
            }

            out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), UTF_8), true);
            out.print(output);
            out.flush();

            if(upgrade) {
                availLock.lock();
                try {
                    synchronized(pendingConnectionsLock) {
                        pendingConnections.put(uuid, socket);
                    }
                    availCheck.signal();
                } finally {
                    availLock.unlock();
                }
                complete = true;
            }
        } catch(Exception e) {
            e.printStackTrace();
        }
        return (upgrade & complete);
    }

    public Socket getSocketWhenAvailable(String uuid) {
        // Should be called by ClientThread
        if(!AuthUtils.isValidUUID(uuid)) return null;
        Socket s = null;
        synchronized(pendingConnectionsLock) {
            s = pendingConnections.remove(uuid);
        }
        if(s != null) return s;
        availLock.lock();
        try {
            Date deadline = new Date();
            deadline.setTime(deadline.getTime() + 15000);
            while(s == null && (new Date()).before(deadline)) {
                availCheck.awaitUntil(deadline);
                synchronized(pendingConnectionsLock) {
                    s = pendingConnections.remove(uuid);
                }
            }
        } catch(InterruptedException e) {
            e.printStackTrace();
        } finally {
            availLock.unlock();
        }
        return s;
    }

    public WebSocketServer(int portNumber, String keyStoreLocation) throws Exception {
        if(new File(keyStoreLocation).canRead()) {
            SSLContext sc = SSLContext.getInstance("TLSv1.2");
            KeyStore keyStore = KeyStore.getInstance("JKS");
            try(InputStream ksin = new FileInputStream(keyStoreLocation)
            ) {
                keyStore.load(ksin, "nopassword".toCharArray());
            }
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(keyStore, "nopassword".toCharArray());
            sc.init(kmf.getKeyManagers(), null, new SecureRandom());
            this.serverSocket = sc.getServerSocketFactory().createServerSocket(portNumber);
        } else {
            System.out.println("Warning: SSL certificate not found, falling back to HTTP");
            this.serverSocket = new ServerSocket(portNumber);
        }

        System.out.println("Server @ " + serverSocket.getInetAddress().getHostAddress() + ":" + serverSocket.getLocalPort());

        Thread upgrader = new Thread(new Runnable() {
            @Override
            public void run() {
                while(true) {
                    try {
                        Socket socket = serverSocket.accept();
                        upgradeFromHttp(socket);
                    } catch(IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        });
        upgrader.start();
    }
}

