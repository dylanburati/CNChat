import ChatUtils.ChatCrypt;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static ChatUtils.Codecs.base64decode;
import static ChatUtils.Codecs.base64encode;
import static java.nio.charset.StandardCharsets.UTF_8;

interface peerUpdateCompat<T> {
    void execute(T thread, String message, String specify);
}

public class ChatServer {

    private static boolean up = true;
    private static volatile List<String> userNames = new ArrayList<>();
    private static final Object userNamesLock = new Object();

    private static String usersHere() {
        StringBuilder retval = new StringBuilder();
        synchronized(userNamesLock) {
            for (String el : userNames) {
                retval.append(" ");
                retval.append(el);
                retval.append("\n");
            }
        }
        return retval.toString();
    }

    public static void main(String[] args) throws IOException {

        class ClientThread extends Thread {

            private final String first = "\nWelcome to Cyber Naysh Chat\ntype ':help' for help\n\n"
                    + (!userNames.isEmpty() ? "<< Here now >>\n" + usersHere() : "<< No one else is here >>\n")
                    + (char) 5 + (char) 17 + "\n";

            private final peerUpdateCompat<ClientThread> peerMessage;
            private PrintWriter out = null;
            private BufferedReader in = null;
            private Cipher cipherE, cipherD;
            private final Object cipherLock = new Object();
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

            private void close() {
                if(out != null) out.close();
                try {
                    if(in != null) in.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }

            @Override
            public void run() {
                try {
                    synchronized(cipherLock) {
                        ChatCrypt chatCrypt = new ChatCrypt(in, out, true);
                        cipherD = chatCrypt.cipherD;
                        cipherE = chatCrypt.cipherE;
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
                boolean messageAll, messageMe;
                String outputLine;
                try {
                    send(first);
                    while ((outputLine = receive()) != null) {
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
                                outputLine += "\nUsers here now:\n";
                                outputLine += usersHere();
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
                        if (messageMe) send(outputLine);
                        if (messageAll) peerMessage.execute(this, outputLine, dmUser);
                        if (!up) System.exit(0);
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                } finally {
                    close();
                }
            }

            private String receive() throws IOException {
                String input = in.readLine();
                if(input == null) return null;
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

            private void send(String outputLine) {
                byte[] data = outputLine.getBytes(UTF_8);
                try {
                    byte[] enc;
                    synchronized(cipherLock) {
                        enc = cipherE.doFinal(data);
                    }
                    out.println(base64encode(enc));
                } catch (IllegalBlockSizeException | BadPaddingException e) {
                    e.printStackTrace();
                }
            }

            String getUserName() {
                return userName;
            }

        }

        int portNumber = 4444;
        ServerSocket serverSocket = new ServerSocket(portNumber);
        System.out.println("Server @ " + serverSocket.getInetAddress().getHostAddress() + ":" + serverSocket.getLocalPort());
        final List<ClientThread> threads = new ArrayList<>();
        peerUpdateCompat<ClientThread> messenger = new peerUpdateCompat<ClientThread>() {
            @Override
            public void execute(ClientThread skip, String message, String user) {
                boolean everyone = user.isEmpty();
                synchronized (threads) {
                    for (Iterator<ClientThread> threadIter = threads.iterator(); threadIter.hasNext(); /* nothing */) {
                        ClientThread currentThread = threadIter.next();
                        if (everyone || user.equals(currentThread.getUserName())) {
                            if (!currentThread.isAlive()) {
                                threadIter.remove();
                                continue;
                            }
                            if (!currentThread.equals(skip)) currentThread.send(message);
                        }
                    }
                }
            }
        };
        while (up) {
            Socket socket = serverSocket.accept();
            System.out.println("Client @ " + socket.getInetAddress().getHostAddress() + ":" + socket.getPort());
            ClientThread thread = new ClientThread(socket, messenger);
            synchronized (threads) {
                threads.add(thread);
            }
            thread.start();
        }

    }

}
