import javax.swing.*;
import javax.swing.text.BadLocationException;
import javax.swing.text.Style;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.WindowEvent;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.Socket;
import java.util.Random;

public class ChatClient extends JFrame {
    private static PrintWriter out;
    private static StyledDocument stdOut;
    private static JScrollBar scrollBar;
    private static String userName;
    private static boolean up = true;
    private static JTextPane chatPane;

    private ChatClient() throws HeadlessException {
        super("CN Chat: " + userName);
        setSize(280, 420);
        setResizable(false);
        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        setLayout(new FlowLayout(FlowLayout.CENTER));
        chatPane = new JTextPane();
        JTextPane textPane = new JTextPane();
        chatPane.setPreferredSize(new Dimension(260, 275));
        chatPane.setEditable(false);
        stdOut = chatPane.getStyledDocument();
        JScrollPane scrollChatPane = new JScrollPane(chatPane);
        scrollBar = scrollChatPane.getVerticalScrollBar();
        add(scrollChatPane);
        textPane.setPreferredSize(new Dimension(260, 90));
        textPane.setMinimumSize(new Dimension(260, 90));
        textPane.addKeyListener(new KeyAdapter() {
            private boolean ctrlDown = false;
            private boolean shiftDown = false;

            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_CONTROL) ctrlDown = true;
                else if (e.getKeyCode() == KeyEvent.VK_SHIFT) shiftDown = true;
                else if (e.getKeyChar() == '\n') {
                    JTextPane tp = (JTextPane) e.getSource();
                    if (ctrlDown || shiftDown) {
                        StyledDocument doc = tp.getStyledDocument();
                        try {
                            doc.insertString(tp.getCaretPosition(), "\n", null);
                        } catch (BadLocationException e1) {
                            e1.printStackTrace();
                        }
                        return;
                    }
                    String input = tp.getText();
                    if (input.contains(":username ")) {
                        String changeRequest = input.substring(input.lastIndexOf(":username ") + 10);
                        if (!changeRequest.equals(userName) && !changeRequest.contains("\n")) {
                            out.println(userName + " is now " + changeRequest + (char) 26);
                            userName = changeRequest;
                            ((JFrame) tp.getTopLevelAncestor()).setTitle("CN Chat: " + userName);
                        }
                    } else if (input.contains(":quit")) {
                        clientClose();
                    } else if (!input.matches("[\\h\\v]*")) {
                        out.println(userName + ": " + input);
                    }
                }
            }

            @Override
            public void keyReleased(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_CONTROL) ctrlDown = false;
                else if (e.getKeyCode() == KeyEvent.VK_SHIFT) shiftDown = false;
                else if (e.getKeyChar() == '\n' && !ctrlDown && !shiftDown) ((JTextPane) e.getSource()).setText(null);
            }
        });
        add(new JScrollPane(textPane));
    }

    private void clientClose() {
        dispatchEvent(new WindowEvent(this, WindowEvent.WINDOW_CLOSING));
    }

    public static void main(String[] args) throws IOException {
        InetAddress host = InetAddress.getByName("0.0.0.0");
        int portNumber = 4444;
        String[] userNames = {"Dylan", "Marissa", "Mom", "Dad"};
        userName = userNames[new Random().nextInt(4)];

        Socket connection = new Socket(host, portNumber);
        out = new PrintWriter(connection.getOutputStream(), true);
        BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream()));

        class MessageDaemon implements Runnable {
            private final BufferedReader in;
            private String newMessage;

            private MessageDaemon(BufferedReader in) {
                this.in = in;
            }

            @Override
            public void run() {
                try {
                    Thread rainbow = new Thread(new Runnable() {
                        @Override
                        public void run() {
                            float h = 0.0f, b = 1.0f, s;
                            try {
                                for (int i = 0; i < 361; i++) {
                                    h += 1.0 / 361.0;
                                    s = (float) Math.sin(i * Math.PI / 360.0);
                                    chatPane.setBackground(Color.getHSBColor(h, s, b));
                                    Thread.sleep(5L);
                                }
                            } catch (InterruptedException ignored) {
                            }
                        }
                    });
                    out.println(userName + (char) 6);
                    Style peerStyle = stdOut.getLogicalStyle(0);
                    Style serverStyle = stdOut.addStyle("server", null);
                    StyleConstants.setForeground(serverStyle, new Color(0, 161, 0));
                    while (up) {
                        if ((newMessage = in.readLine()) != null) {
                            if (newMessage.length() != (newMessage = newMessage.replaceAll("[\\x00-\\x1f\\x7f]", "")).length())
                                stdOut.setLogicalStyle(stdOut.getLength(), serverStyle);
                            else stdOut.setLogicalStyle(stdOut.getLength(), peerStyle);
                            stdOut.insertString(stdOut.getLength(), newMessage + "\n", null);
                            String command;
                            if ((command = newMessage.toLowerCase()).contains(":color ")) {
                                if (rainbow.isAlive()) rainbow.interrupt();
                                if (command.contains("blue")) chatPane.setBackground(Color.BLUE);
                                else if (command.contains("red")) chatPane.setBackground(Color.RED);
                                else if (command.contains("white") || command.contains("reset"))
                                    chatPane.setBackground(Color.WHITE);
                                else if (command.contains("rainbow")) {
                                    rainbow.start();
                                } else {
                                    int ccIndex = command.lastIndexOf(":color ") + 7;
                                    if (command.contains(",")) {
                                        String[] rgb = command.substring(ccIndex).replaceAll("[^,0-9]", "").split(",");
                                        if (rgb.length == 3) {
                                            int r = Integer.parseInt(rgb[0]);
                                            int g = Integer.parseInt(rgb[1]);
                                            int b = Integer.parseInt(rgb[2]);
                                            if (r / 256 == 0 && g / 256 == 0 && b / 256 == 0)
                                                chatPane.setBackground(new Color(r, g, b));
                                        }
                                    }
                                    if (command.length() >= ccIndex + 6) {
                                        String customColor = command.substring(ccIndex, ccIndex + 6);
                                        if (customColor.matches("[0-9a-f]{6}"))
                                            chatPane.setBackground(new Color(Integer.parseInt(customColor, 16)));
                                    }
                                }
                            }
                            scrollBar.setValue(scrollBar.getMaximum());
                        }
                    }
                } catch (IOException | BadLocationException | NumberFormatException e) {
                    e.printStackTrace();
                }
            }
        }

        new ChatClient().setVisible(true);
        Thread clientThread = new Thread(new MessageDaemon(in));
        clientThread.start();
        Runtime.getRuntime().addShutdownHook(new Thread(
                new Runnable() {
                    @Override
                    public void run() {
                        out.println(userName + (char) 4);
                        up = false;
                    }
                }
        ));
        try {
            clientThread.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
}
