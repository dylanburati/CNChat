package com.dylanburati.cnchat.server.ChatUtils;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static java.nio.charset.StandardCharsets.UTF_8;

public class WebSocketServer {
    public interface AuthCallback {
        void addAuthorizedUuid(String uuid);
    }

    private ServerSocket serverSocket = null;
    private final Map<String, Socket> pendingConnections = new HashMap<>();
    private final Object pendingConnectionsLock = new Object();
    public final List<String> authorized = new ArrayList<>();
    public final Object authorizedLock = new Object();
    private static final DateTimeFormatter dateFormatter = DateTimeFormatter
            .ofPattern("EEE, dd MMM yyyy HH:mm:ss z", Locale.ENGLISH).withZone(ZoneId.of("GMT"));

    private final Lock availLock = new ReentrantLock();
    private final Condition availCheck = availLock.newCondition();

    private boolean upgradeFromHttp(Socket socket) {
        String input = "";
        try {
            // intentionally not closed
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), UTF_8));
            StringBuilder inputB = new StringBuilder();
            String inLine;
            while((inLine = in.readLine()) != null) {
                if(inLine.isEmpty()) break;
                inputB.append(inLine).append("\n");
            }
            if(inputB.length() > 0) {
                input = inputB.substring(0, inputB.length() - 1);
            }
        } catch (IOException e) {
            return false;
        }

        String uuid = getUpgradeUUID(input);
        HttpResponse response = parseUpgrade(uuid, input);
        response = response.withHeader("Date", dateFormatter.format(ZonedDateTime.now()));
        if (response.code != 101) {
            response = response.withHeader("Content-Length", String.valueOf(response.output.length()));
        }
        try {
            // intentionally not closed
            PrintWriter out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), UTF_8), true);
            out.print(response.getStatusLine() + "\r\n");
            for (String header : response.getHeaderLines()) {
                out.print(header + "\r\n");
            }
            out.print(response.output.toString());
            out.flush();

            if (response.code != 101) {
                return false;
            }
            availLock.lock();
            try {
                synchronized(pendingConnectionsLock) {
                    pendingConnections.put(uuid, socket);
                }
                availCheck.signal();
            } finally {
                availLock.unlock();
            }
            return true;
        } catch(IOException e) {
            e.printStackTrace();
        }
        return false;
    }

    private HttpResponse parseUpgrade(String uuid, String input) {
        if (uuid == null) {
            return new HttpResponse(400);
        }

        synchronized(authorizedLock) {
            if (!authorized.remove(uuid)) {
                System.out.format("WebSocket rejected @ %s\n", uuid);
                return new HttpResponse(400);
            }
        }

        boolean isUpgrade = false;
        boolean isWebsocket = false;
        String wsKey = null;
        String wsKeyHeaderName = "Sec-WebSocket-Key: ";
        for (String line : input.split("[\\r\\n]+")) {
            isUpgrade |= line.equalsIgnoreCase("Connection: Upgrade");
            isWebsocket |= line.equalsIgnoreCase("Upgrade: websocket");
            if (line.length() > wsKeyHeaderName.length() &&
                    line.substring(0, wsKeyHeaderName.length()).equalsIgnoreCase(wsKeyHeaderName)) {
                wsKey = line.substring(wsKeyHeaderName.length());
            }
        }

        if (!isUpgrade || !isWebsocket || wsKey == null) {
            return new HttpResponse(400);
        }

        HttpResponse result = new HttpResponse(101)
                .withHeader("Connection", "Upgrade")
                .withHeader("Upgrade", "WebSocket");
        try {
            byte[] wsKeyEnc = MessageDigest.getInstance("SHA-1").digest(
                    (wsKey + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(UTF_8));
            result = result.withHeader("Sec-WebSocket-Accept", Base64.getEncoder().encodeToString(wsKeyEnc));
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-1 algorithm not found");
        }
        return result;
    }

    private String getUpgradeUUID(String input) {
        Scanner sc = new Scanner(input);
        if (!sc.hasNext() || !"GET".equals(sc.next())) {
            return null;
        }
        if (!sc.hasNext()) {
            return null;
        }
        String path = sc.next();
        if (path.length() < 33) {
            return null;
        }
        String uuid = path.substring(1);
        if (!AuthUtils.isValidUUID(uuid)) {
            return null;
        }
        return uuid;
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

    public WebSocketServer(int portNumber) throws IOException {
        this.serverSocket = new ServerSocket(portNumber);

        System.out.println("Server @ " + serverSocket.getInetAddress().getHostAddress() + ":" + serverSocket.getLocalPort());

        Thread upgrader = new Thread(() -> {
            while(true) {
                try {
                    Socket socket = serverSocket.accept();
                    upgradeFromHttp(socket);
                } catch(IOException e) {
                    e.printStackTrace();
                }
            }
        });
        upgrader.start();
    }
}

