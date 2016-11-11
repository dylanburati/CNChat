import javax.swing.*;
import javax.swing.text.*;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ChatClient extends JFrame {
    private static PrintWriter out;
    private static StyledDocument stdOut;
    private static JScrollBar scrollBar;
    private static String userName;
    private static boolean up = true;
    private static JTextPane chatPane;

    private ChatClient() throws HeadlessException {
        super("CN Chat: " + userName);
        setIconImage(new ImageIcon(getClass().getResource("Icon.png")).getImage());
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
                    if (input.contains(":quit")) {
                        clientClose();
                    } else if (input.contains(":username ")) {
                        String changeRequest = input.substring(input.lastIndexOf(":username ") + 10);
                        if (!changeRequest.equals(userName) && changeRequest.matches("[^\\n:]+")) {
                            out.println(base16encode((char) 26 + userName + (char) 26 + changeRequest));
                            userName = changeRequest;
                            ((JFrame) tp.getTopLevelAncestor()).setTitle("CN Chat: " + userName);
                        }
                    } else if(input.contains(":format")) {
                        out.println(base16encode("" + (char) 17));
                    } else if(input.contains(":unformat")) {
                        out.println(base16encode("" + (char) 17 + (char) 17));
                    } else if (!input.matches("[\\h\\v]*")) {
                        if (input.startsWith(":dm ")) {
                            out.println(base16encode((char)15 + userName + ": " + input + (char)14));
                        }
                        else out.println(base16encode(userName + ": " + input));
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

    private static String base16encode(String in) {
        byte[] b256 = in.getBytes();
        byte[] b16 = new byte[2 * b256.length];
        int idx = 0;
        for (int cp : b256) {
            b16[idx++] = (byte) (((cp & (15 << 4)) >> 4)+64);
            b16[idx++] = (byte) ((cp & 15)+64);
        }
        return new String(b16);
    }

    private static String base16decode(String in) {
        byte[] b16 = in.getBytes();
        byte[] b256 = new byte[b16.length / 2];
        for (int i = 0; i < b16.length; i += 2) {
            b256[i / 2] = (byte) (((b16[i] - 64) << 4) + (b16[i + 1] - 64));
        }
        return new String(b256);
    }

    private static class MarkdownUtils {
        private static boolean format = false;
        private static int[] genFormatMap(String text) {
            int textLen = text.length();
            if(textLen == 0) return new int[0];
            int[] map = new int[textLen];
            Arrays.fill(map, 0);
            text = text.replaceAll("\\\\(?=\\*{1,2}|_{1,2}|`)", "\\\u001b");
            for(String regex : new String[]{"(?<!\\\\|\\*)(\\*)[^\\*]+(?<!\\\\|\\*)(\\*)", "(?<!\\\\|_)(_)[^_]+(?<!\\\\|_)(_)",
                    "(?<!\\\\)(\\*\\*).+?(?<!\\\\)(\\*\\*)", "(?<!\\\\)(__).+?(?<!\\\\)(__)", "(?<!\\\\|`)(`).+?(?<!\\\\|`)(`)"}) {
                Matcher m = Pattern.compile(regex).matcher(text);
                boolean backtick = regex.contains("`");
                while (m.find()) {
                    int currentAction = backtick ? 4 : m.end(1) - m.start();
                    for (int i = m.start(); i < m.end(1); i++) map[i] |= -1;
                    for (int i = m.end(1); i < m.start(2); i++) map[i] |= currentAction;
                    for (int i = m.start(2); i < m.end(); i++) map[i] |= -1;
                }
            }
            for (String escregex : new String[]{"\\\\(?=\\*{1,2}|_{1,2}|`)", "\u001b"}) {
                Matcher esc = Pattern.compile(escregex).matcher(text);
                while (esc.find()) {
                    map[esc.start()] = -1;
                }
            }
            return map;
        }
    }

    public static void main(String[] args) throws IOException {
        InetAddress host = InetAddress.getByName("0.0.0.0");
        int portNumber = 4444;
        java.util.List<String> userNames = new ArrayList<>();
        Collections.addAll(userNames, "Lil B", "KenM", "Ken Bone", "Tai Lopez", "Hugh Mungus",
                "Donald Trump", "Hillary Clinton", "Jesus", "VN", "Uncle Phil",
                "Watery Westin", "A Wild KB");
        userName = userNames.remove(new Random().nextInt(userNames.size()));

        Socket connection = new Socket(host, portNumber);
        out = new PrintWriter(connection.getOutputStream(), true);
        BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream()));

        class MessageDaemon implements Runnable {
            private final BufferedReader in;

            private MessageDaemon(BufferedReader in) {
                this.in = in;
            }

            @Override
            public void run() {
                try {
                    String newMessage;
                    Thread rainbow = new Thread();
                    out.println(base16encode((char) 6 + userName));
                    Style peerStyle = stdOut.getLogicalStyle(0);
                    Style serverStyle = stdOut.addStyle("server", null);
                    StyleConstants.setForeground(serverStyle, new Color(0, 161, 0));
                    StyleConstants.setFontFamily(serverStyle, "Lucida Console");
                    Style directStyle = stdOut.addStyle("direct", peerStyle);
                    StyleConstants.setForeground(directStyle, new Color(81, 0, 241));
                    while (up) {
                        if ((newMessage = in.readLine()) != null) {
                            newMessage = base16decode(newMessage);
                            final int header = newMessage.isEmpty() ? -1 : newMessage.codePointAt(0);
                            if (header == 21) {
                                if (newMessage.length() == 1) {
                                    if (!userNames.isEmpty()) {
                                        userName = userNames.remove(new Random().nextInt(userNames.size()));
                                    } else {
                                        userName = Integer.toString(36 * 36 * 36 + new Random().nextInt(35 * 36 * 36 * 36), 36);
                                    }
                                    out.println(base16encode((char) 6 + userName));
                                } else {
                                    userName = newMessage.substring(1);
                                    stdOut.setLogicalStyle(stdOut.getLength(), serverStyle);
                                    stdOut.insertString(stdOut.getLength(), "<< That username is taken >>\n", null);
                                }
                                ((JFrame) chatPane.getTopLevelAncestor()).setTitle("CN Chat: " + userName);
                                continue;
                            }
                            stdOut.setLogicalStyle(stdOut.getLength(), peerStyle);
                            MarkdownUtils.format = newMessage.length() != (newMessage = newMessage.replace(""+(char)17, "")).length();
                            if (newMessage.length() != (newMessage = newMessage.replaceAll("[\\x0e\\x0f\\x7f-\\x9f]", "")).length()) {
                                stdOut.setLogicalStyle(stdOut.getLength(), directStyle);
                            }
                            if (newMessage.length() != (newMessage = newMessage.replaceAll("[\\x00-\\x07\\x10-\\x1f]", "")).length()) {
                                stdOut.setLogicalStyle(stdOut.getLength(), serverStyle);
                                stdOut.insertString(stdOut.getLength(), newMessage + "\n", null);
                                continue;
                            }
                            final String command = newMessage.toLowerCase();
                            if (command.contains(":color ")) {
                                if (rainbow.isAlive()) rainbow.interrupt();
                                if (command.contains("white") || command.contains("reset"))
                                    chatPane.setBackground(Color.WHITE);
                                else if (command.contains("rainbow")) {
                                    rainbow = new Thread(new Runnable() {
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
                                    rainbow.start();
                                } else {
                                    final int ccIndex = command.lastIndexOf(":color ") + 7;
                                    if (command.contains(",")) {
                                        final String[] rgb = command.substring(ccIndex).replaceAll("[^,0-9]", "").split(",");
                                        if (rgb.length == 3) {
                                            int r = Integer.parseInt(rgb[0]);
                                            int g = Integer.parseInt(rgb[1]);
                                            int b = Integer.parseInt(rgb[2]);
                                            if (r / 256 == 0 && g / 256 == 0 && b / 256 == 0)
                                                chatPane.setBackground(new Color(r, g, b));
                                        }
                                    }
                                    if (command.length() >= ccIndex + 6) {
                                        final String customColor = command.substring(ccIndex, ccIndex + 6);
                                        if (customColor.matches("[0-9a-f]{6}"))
                                            chatPane.setBackground(new Color(Integer.parseInt(customColor, 16)));
                                    }
                                }
                            } else {
                                if(MarkdownUtils.format) {
                                    int[] format = MarkdownUtils.genFormatMap(newMessage);
                                    for (int i = 0, cl = stdOut.getLength(); i < format.length; i++, cl++) {
                                        if (format[i] == -1) {
                                            cl--;
                                            continue;
                                        }
                                        if (format[i] == 0) {
                                            stdOut.insertString(cl, "" + newMessage.charAt(i), null);
                                            continue;
                                        }
                                        SimpleAttributeSet fmt = new SimpleAttributeSet();
                                        if ((format[i] & 1) != 0) StyleConstants.setItalic(fmt, true);
                                        if ((format[i] & 2) != 0) StyleConstants.setBold(fmt, true);
                                        if ((format[i] & 4) != 0) StyleConstants.setFontFamily(fmt, "Lucida Console");
                                        stdOut.insertString(cl, "" + newMessage.charAt(i), fmt);
                                    }
                                    stdOut.insertString(stdOut.getLength(), "\n", null);
                                } else {
                                    stdOut.insertString(stdOut.getLength(), newMessage+"\n", null);
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
                        out.println(base16encode((char) 4 + userName));
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
