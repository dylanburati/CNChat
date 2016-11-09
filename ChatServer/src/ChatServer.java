import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

interface peerUpdateCompat<T> {
    void execute(T thread, String message, String specify);
}

public class ChatServer {

    private static boolean up = true;
    private static volatile List<String> userNames = new ArrayList<>();
    private static final Object userNamesLock = new Object();

    public static void main(String[] args) throws IOException {

        class ClientThread extends Thread {

            private final String first = "\nWelcome to the Cyber Naysh Chat Room\ntype ':help' for help\n\n"
                    + (!userNames.isEmpty() ? "\005<< Here now >>\n\005 " + String.join("\n\005 ", userNames) : "\005<< No one else is here >>")
                    + "\n";
            private final peerUpdateCompat<ClientThread> peerMessage;
            private PrintWriter out = null;
            private BufferedReader in = null;
            private String userName, dmUser = "";
            private boolean endDm = false, markDown = false;

            private ClientThread(Socket clientSocket, peerUpdateCompat<ClientThread> peerMessage) {
                this.peerMessage = peerMessage;
                try {
                    this.out = new PrintWriter(clientSocket.getOutputStream(), true);
                    this.in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }

            @Override
            public void run() {
                boolean messageAll, messageMe;
                String outputLine;
                try {
                    out.println(first);
                    while ((outputLine = in.readLine()) != null) {
                        messageAll = messageMe = true;
                        if (outputLine.contains(":serverquit")) {
                            up = false;
                            outputLine = "<< " + userName + " ended the chat >>" + (char) 5;
                        } else if (outputLine.contains(":help")) {
                            messageAll = false;
                            outputLine = "\nCommands start with a colon (:)" + (char) 5 +
                                    "\n:status sends you key info" + (char) 5 +
                                    "\n:dm <user> sends a direct message" + (char) 5 +
                                    "\n:username <new username>" + (char) 5 +
                                    "\n:quit closes your chat box" + (char) 5 +
                                    "\n:serverquit ends the chat" + (char) 5 + "\n";
                        } else if (outputLine.contains(":status")) {
                            messageAll = false;
                            outputLine = "<< Status >>" + (char) 5 +
                                    "\nUsername: " + userName + (char) 5 +
                                    "\nFormat: " + (markDown ? "Markdown" : "plain text") + (char) 5 +
                                    "\n :format for Markdown" + (char) 5 +
                                    "\n :unformat for plain text" + (char) 5;
                            if(userNames.size() > 1) {
                                outputLine += "\nUsers here now:" + (char) 5 + "\n";
                                for (String usr : userNames) {
                                    if (!usr.equals(userName)) outputLine += " " + usr + (char) 5 + "\n";
                                }
                            } else {
                                outputLine += "No one else is here" + (char) 5 + "\n";
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
                                    if (outputLine.contains("" + (char) 14)) {
                                        messageAll = false;
                                        outputLine = "<< Can't send empty DM >>" + (char) 5;
                                    } else {
                                        String dmRequest = outputLine.substring(rcvIndex + 4);
                                        if (userNames.contains(dmRequest)) {
                                            dmUser = dmRequest;
                                            outputLine = userName + ": << DM to " + dmUser + " >>";
                                        } else {
                                            dmUser = "" + (char) 24;
                                            messageAll = false;
                                            outputLine = "<< User not found >>" + (char) 5;
                                        }
                                    }
                                }
                            } else if (outputLine.endsWith("" + (char) 14)) {
                                endDm = true;
                            }
                            if(command == 17) {
                                messageAll = messageMe = false;
                                markDown = outputLine.length() == 1;
                            }
                        }
                        if (dmUser.equals(""+(char)24)) {
                            messageMe = !messageAll;
                            messageAll = false;
                        } else if (!dmUser.isEmpty() && !endDm) {
                            outputLine += (char) 15;
                        }
                        if(markDown) outputLine += (char)17;
                        if (messageMe) out.println(outputLine);
                        if (messageAll) peerMessage.execute(this, outputLine, dmUser);
                        if (endDm) {
                            dmUser = "";
                            endDm = false;
                        }
                        if (!up) System.exit(0);
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }

            private void peerMessage(String outputLine) {
                out.println(outputLine);
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
