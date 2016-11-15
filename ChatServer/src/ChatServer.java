import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

import static ChatUtils.Codecs.*;
import static java.nio.charset.StandardCharsets.UTF_8;

interface peerUpdateCompat<T> {
    void execute(T thread, String message, String specify);
}

public class ChatServer {

    private static boolean up = true;
    private static volatile List<String> userNames = new ArrayList<>();
    private static final Object userNamesLock = new Object();

    public static void main(String[] args) throws IOException {

        class ClientThread extends Thread {

            private final String first = "\nWelcome to Cyber Naysh Chat\ntype ':help' for help\n\n"
                    + (!userNames.isEmpty() ? "<< Here now >>\n " + String.join("\n ", userNames) : "<< No one else is here >>")
                    + (char) 5 + (char) 17 + "\n";
            private final peerUpdateCompat<ClientThread> peerMessage;
            private PrintWriter out = null;
            private BufferedReader in = null;
            private String userName, dmUser = "";
            private boolean markDown = false;

            private ClientThread(Socket clientSocket, peerUpdateCompat<ClientThread> peerMessage) {
                this.peerMessage = peerMessage;
                try {
                    this.out = new PrintWriter(new OutputStreamWriter(clientSocket.getOutputStream(), UTF_8), true);
                    this.in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream(), UTF_8));
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
