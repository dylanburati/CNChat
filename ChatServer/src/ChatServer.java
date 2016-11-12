import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

interface peerUpdateCompat<T> {
    void execute(T thread, String message, String specify);
}

public class ChatServer {

    private static boolean up = true;
    private static Charset cset = Charset.forName("UTF-8");
    private static volatile List<String> userNames = new ArrayList<>();
    private static final Object userNamesLock = new Object();

    private static String base64encode(String in) {
        byte[] b256 = in.getBytes(cset);
        int field;
        int tail = b256.length % 3;
        int length64 = (b256.length / 3 * 4) + new int[]{1,3,4}[tail];
        byte[] b64 = new byte[length64];
        int i256, i64;
        for (i256 = i64 = 0; i256 < b256.length - tail; i256 += 3) {
            field = (Byte.toUnsignedInt(b256[i256]) << 16) |
                    (Byte.toUnsignedInt(b256[i256 + 1]) << 8) |
                    Byte.toUnsignedInt(b256[i256 + 2]);
            b64[i64++] = (byte) (((field & (63 << 18)) >> 18) + 63);
            b64[i64++] = (byte) (((field & (63 << 12)) >> 12) + 63);
            b64[i64++] = (byte) (((field & (63 << 6)) >> 6) + 63);
            b64[i64++] = (byte) ((field & 63) + 63);
        }
        switch (tail) {
            case 1:
                field = Byte.toUnsignedInt(b256[i256]);
                b64[i64++] = (byte) (((field & (63 << 2)) >> 2) + 63);
                b64[i64++] = (byte) ((field & 3) + 63);
                break;
            case 2:
                field = (Byte.toUnsignedInt(b256[i256]) << 8) |
                        Byte.toUnsignedInt(b256[i256 + 1]);
                b64[i64++] = (byte) (((field & (63 << 10)) >> 10) + 63);
                b64[i64++] = (byte) (((field & (63 << 4)) >> 4) + 63);
                b64[i64++] = (byte) ((field & 15) + 63);
        }
        b64[i64] = (byte) (tail + 63);
        return new String(b64, cset);
    }

    private static String base64decode(String in) {
        byte[] b64 = in.getBytes(cset);
        int field;
        int tail256 = (int) b64[b64.length - 1] - 63;
        int tail64 = new int[]{1,3,4}[tail256];
        int length256 = (b64.length - tail64) * 3 / 4 + tail256;
        byte[] b256 = new byte[length256];
        int i256, i64;
        for (i64 = i256 = 0; i64 < b64.length - tail64; i64 += 4) {
            field = ((Byte.toUnsignedInt(b64[i64]) - 63) << 18) |
                    ((Byte.toUnsignedInt(b64[i64 + 1]) - 63) << 12) |
                    ((Byte.toUnsignedInt(b64[i64 + 2]) - 63) << 6) |
                    (Byte.toUnsignedInt(b64[i64 + 3]) - 63);
            b256[i256++] = (byte) ((field & (255 << 16)) >> 16);
            b256[i256++] = (byte) ((field & (255 << 8)) >> 8);
            b256[i256++] = (byte) (field & 255);
        }
        switch (tail256) {
            case 1:
                field = ((Byte.toUnsignedInt(b64[i64]) - 63) << 2) |
                        (Byte.toUnsignedInt(b64[i64 + 1]) - 63);
                b256[i256] = (byte) field;
                break;
            case 2:
                field = ((Byte.toUnsignedInt(b64[i64]) - 63) << 10) |
                        ((Byte.toUnsignedInt(b64[i64 + 1]) - 63) << 4) |
                        (Byte.toUnsignedInt(b64[i64 + 2]) - 63);
                b256[i256++] = (byte) ((field & (255 << 8)) >> 8);
                b256[i256] = (byte) (field & 255);
        }
        return new String(b256, cset);
    }

    public static void main(String[] args) throws IOException {

        class ClientThread extends Thread {

            private final String first = "\nWelcome to Cyber Naysh Chat\ntype ':help' for help\n\n"
                    + (!userNames.isEmpty() ? "<< Here now >>\n " + String.join("\n ", userNames) : "<< No one else is here >>")
                    + (char) 5 + "\n";
            private final peerUpdateCompat<ClientThread> peerMessage;
            private PrintWriter out = null;
            private BufferedReader in = null;
            private String userName, dmUser = "";
            private boolean markDown = false;

            private ClientThread(Socket clientSocket, peerUpdateCompat<ClientThread> peerMessage) {
                this.peerMessage = peerMessage;
                try {
                    this.out = new PrintWriter(new OutputStreamWriter(clientSocket.getOutputStream(), cset), true);
                    this.in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream(), cset));
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }

            @Override
            public void run() {
                boolean messageAll, messageMe;
                String outputLine;
                try {
                    out.println(base64encode(first));
                    while ((outputLine = in.readLine()) != null) {
                        outputLine = base64decode(outputLine);
                        messageAll = messageMe = true;
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
                                outputLine += "No one else is here" + "\n";
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
                            } else if (command == 4) {
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
                                        int delimiter = Math.min(outputLine.indexOf("\r"), outputLine.indexOf("\n"));
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
                        if (messageMe) out.println(base64encode(outputLine));
                        if (messageAll) peerMessage.execute(this, outputLine, dmUser);
                        if (!up) System.exit(0);
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }

            private void peerMessage(String outputLine) {
                out.println(base64encode(outputLine));
            }

            String getUserName() {
                return userName;
            }

        }

        int portNumber = 4444;
        ServerSocket serverSocket = new ServerSocket(portNumber);
        System.out.println(serverSocket.getInetAddress());
        List<ClientThread> threads = new ArrayList<>();
        peerUpdateCompat<ClientThread> messenger = new peerUpdateCompat<ClientThread>() {
            @Override
            public void execute(ClientThread skip, String message, String user) {
                boolean everyone = user.isEmpty();
                for (ClientThread currentThread : threads) {
                    if (everyone || user.equals(currentThread.getUserName())) {
                        if (!currentThread.equals(skip)) currentThread.peerMessage(message);
                    }
                }
            }
        };
        while (up) {
            Socket socket = serverSocket.accept();
            ClientThread thread = new ClientThread(socket, messenger);
            threads.add(thread);
            thread.start();
        }

    }

}
