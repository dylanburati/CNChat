import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

interface peerUpdateCompat<T> {
    void execute(T thread, String message);
}

public class ChatServer {

    private static boolean up = true;
    private static List<String> userNames = new ArrayList<>();

    public static void main(String[] args) throws IOException {

        class ClientThread extends Thread {

            private final String first = "\nWelcome to the Cyber Naysh Chat Room\ntype ':help' for help\n";
            private final peerUpdateCompat<ClientThread> peerMessage;
            private PrintWriter out = null;
            private BufferedReader in = null;
            private String userName;

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
                            messageAll = up = false;
                        } else if (outputLine.contains(":help")) {
                            messageAll = false;
                            outputLine = "\nCommands start with a colon (:)" + (char)5 +
                            "\n:username <new username>" + (char)5 +
                            "\n:quit closes your chat box" + (char)5 +
                            "\n:serverquit ends the chat" + (char)5 + "\n";
                        }
                        else if (outputLine.contains(""+(char)6)) {
                            String nameRequest = outputLine.replace(""+(char)6,"");
                            if(userNames.contains(nameRequest)) {
                                messageAll = false;
                                outputLine = ""+(char)21;
                            } else {
                                messageMe = false;
                                userName = nameRequest;
                                userNames.add(userName);
                                outputLine = "<< " + outputLine + " joined the chat >>";
                            }
                        }
                        else if (outputLine.contains(""+(char)26)) {
                            String nameRequest = outputLine.substring(outputLine.lastIndexOf(26)+1);
                            if(userNames.contains(nameRequest)) {
                                messageAll = false;
                                outputLine = outputLine.substring(0,outputLine.indexOf(26))+(char)21;
                            } else {
                                messageMe = false;
                                userNames.remove(userName);
                                userName = nameRequest;
                                userNames.add(userName);
                                outputLine = "<< " + outputLine + " >>";
                            }
                        }
                        else if (outputLine.contains(""+(char)4)) {
                            messageMe = false;
                            userNames.remove(userName);
                            outputLine = "<< " + outputLine + " left the chat >>";
                        }
                        if(messageMe) out.println(outputLine);
                        if(messageAll) peerMessage.execute(this, outputLine);
                        if (!up) break;
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }

            private void peerMessage(String outputLine) {
                out.println(outputLine);
            }

        }

        int portNumber = 4444;
        ServerSocket serverSocket = new ServerSocket(portNumber);
        System.out.println(serverSocket.getInetAddress());
        List<ClientThread> threads = new ArrayList<>();
        peerUpdateCompat<ClientThread> messenger = new peerUpdateCompat<ClientThread>() {
            @Override
            public void execute(ClientThread skip, String message) {
                for (ClientThread currentThread : threads) {
                    if (!currentThread.equals(skip)) currentThread.peerMessage(message);
                }
            }
        };
        while (up) {
            Socket socket = serverSocket.accept();
            ClientThread thread = new ClientThread(socket, messenger);
            threads.add(thread);
            thread.start();
        }
        for (Thread t : threads) {
            try {
                t.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

    }

}
