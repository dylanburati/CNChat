import ChatUtils.ChatCrypt;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.swing.*;
import javax.swing.text.*;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.WindowEvent;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static ChatUtils.Codecs.base64decode;
import static ChatUtils.Codecs.base64encode;
import static java.nio.charset.StandardCharsets.UTF_8;

public class ChatClient extends JFrame {
    private static StyledDocument stdOut;
    private static JScrollBar scrollBar;
    private static String userName;
    private static JTextPane chatPane;
    private static URL host;
    private static HttpURLConnection conn;
    private static String uuid;

    private static volatile java.util.List<String> outQueue = new ArrayList<>();
    private static final Object outQueueLock = new Object();
    private static Cipher cipherD, cipherE;
    private static final Object cipherLock = new Object();

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
                    input = input.replaceAll("[\\x00-\\x07\\x0e-\\x1f\\x7f-\\x9f]", "");
                    if (input.contains(":quit")) {
                        clientClose();
                    } else if (input.contains(":username ")) {
                        String changeRequest = input.substring(input.lastIndexOf(":username ") + 10);
                        if (!changeRequest.equals(userName) && changeRequest.matches("[^\\n:]+")) {
                            enqueue((char) 26 + userName + (char) 26 + changeRequest);
                            userName = changeRequest;
                            ((JFrame) tp.getTopLevelAncestor()).setTitle("CN Chat: " + userName);
                        }
                    } else if(input.contains(":format")) {
                        enqueue("" + (char) 17);
                    } else if(input.contains(":unformat")) {
                        enqueue("" + (char) 17 + (char) 17);
                    } else if (!input.matches("[ \\t\\xA0\\u1680\\u180e\\u2000-\\u200a\\u202f\\u205f\\u3000" +
                            "\\n\\x0B\\f\\r\\x85\\u2028\\u2029]*")) {
                        if (input.startsWith(":dm ")) {
                            enqueue((char)15 + userName + ": " + input);
                        }
                        else enqueue(userName + ": " + input);
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
        try {
            Font mono = Font.createFont(Font.TRUETYPE_FONT, getClass().getResourceAsStream("Inconsolata-Regular.ttf"));
            GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(mono);
        } catch (IOException | FontFormatException e) {
            e.printStackTrace();
        }
    }

    private static void enqueue(String outputLine) {
        byte[] data = outputLine.getBytes(UTF_8);
        try {
            byte[] enc;
            synchronized(cipherLock) {
                enc = cipherE.doFinal(data);
            }
            synchronized(outQueueLock) {
                outQueue.add(base64encode(enc));
            }
        } catch (IllegalBlockSizeException | BadPaddingException e) {
            e.printStackTrace();
        }
    }

    private static String decrypt(String input) throws IOException {
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

    private static void refresh() throws IOException {
        conn = (HttpURLConnection) host.openConnection();
    }

    private void clientClose() {
        dispatchEvent(new WindowEvent(this, WindowEvent.WINDOW_CLOSING));
    }

    private static class MarkdownUtils {
        private static boolean format = false;
        private static int[] genFormatMap(String text) {
            int textLen = text.length();
            if(textLen == 0) return new int[0];
            int[] map = new int[textLen];
            text = text.replaceAll("\\\\\\\\(?=\\*{1,2}|_{1,2}|`)", "\\\\\000");
            StringBuilder mkText = new StringBuilder(text);
            for(String regex : new String[]{"(?<!\\\\)(\\*\\*).+(?<!\\\\)(\\*\\*)", "(?<!\\\\)(__).+(?<!\\\\)(__)",
                    "(?<!\\\\)(\\*)[^\\*]+(?<!\\\\|\\*)(\\*)", "(?<!\\\\)(_)[^_]+(?<!\\\\|_)(_)",
                    "(?<!\\\\)(`)[^`]+(?<!\\\\)(`)"}) {
                Matcher m = Pattern.compile(regex, Pattern.DOTALL).matcher(mkText);
                boolean backtick = regex.contains("`");
                while (m.find()) {
                    int currentAction = backtick ? 4 : m.end(1) - m.start();
                    boolean redundant = !backtick;
                    for(int i = m.end(1); redundant && i < m.start(2); i++) {
                        redundant = ((map[i] & currentAction) != 0);
                    }
                    if(redundant) continue;
                    for (int i = m.start(); i < m.end(1); i++) {
                        map[i] |= -1;
                        mkText.replace(i, i+1, "\000");
                    }
                    for (int i = m.end(1); i < m.start(2); i++) {
                        map[i] |= currentAction;
                    }
                    for (int i = m.start(2); i < m.end(); i++) {
                        map[i] |= -1;
                        mkText.replace(i, i+1, "\000");
                    }
                }
            }
            for (String escregex : new String[]{"\\\\(?=\\*{1,2}|_{1,2}|`)", "\000"}) {
                Matcher esc = Pattern.compile(escregex).matcher(text);
                while (esc.find()) {
                    map[esc.start()] = -1;
                }
            }
            return map;
        }
    }

    public static void main(String[] args) throws IOException {
        String hostName = "0.0.0.0";
        String path = new File(ChatClient.class.getProtectionDomain().getCodeSource().getLocation().getFile()).
                getParent() + System.getProperty("file.separator") + "config.txt";
        try(BufferedReader config = new BufferedReader(new InputStreamReader(new FileInputStream(path), UTF_8))) {
            Pattern noComment = Pattern.compile("^\\p{javaWhitespace}*.");
            String remote;
            while((remote = config.readLine()) != null) {
                Matcher m = noComment.matcher(remote);
                if (m.find() && !remote.startsWith("#", m.end() - 1)) {
                    hostName = remote.substring(m.end() - 1);
                    break;
                }
            }
        } catch(IOException ignored) {
        }
        int portNumber = 8080;

        final java.util.List<String> userNames = new ArrayList<>();
        Collections.addAll(userNames, "Lil B", "KenM", "Ken Bone", "Tai Lopez", "Hugh Mungus",
                "Donald Trump", "Hillary Clinton", "Jesus", "VN", "Uncle Phil",
                "Watery Westin", "A Wild KB");
        final Random random = new Random();
        userName = userNames.remove(random.nextInt(userNames.size()));

        host = new URL("http", hostName, portNumber, "");
        System.out.println("Connecting to " + InetAddress.getByName(hostName).getHostAddress() + ":" + portNumber);

        class MessageHandler {
            private final Runnable rainbowRun = new Runnable() {
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
            };

            private Thread rainbow = new Thread();
            private Style peerStyle;
            private Style serverStyle;
            private Style directStyle;

            private MessageHandler() {
                this.peerStyle = stdOut.getLogicalStyle(0);
                this.serverStyle = stdOut.addStyle("server", null);
                StyleConstants.setForeground(serverStyle, new Color(0, 161, 0));
                StyleConstants.setFontFamily(serverStyle, "Inconsolata");
                this.directStyle = stdOut.addStyle("direct", peerStyle);
                StyleConstants.setForeground(directStyle, new Color(81, 0, 241));
            }

            private boolean sendAndReceive() throws IOException, BadLocationException {
                refresh();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                try(PrintWriter out = new PrintWriter(new OutputStreamWriter(conn.getOutputStream(), UTF_8), true)
                ) {
                    synchronized (outQueueLock) {
                        while(outQueue.size() > 0) {
                            out.print(outQueue.remove(0));
                            if(outQueue.size() > 0) out.print("\r\n");
                        }
                    }
                    out.close();
                }

                String input;
                boolean sane = true;
                try(BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream(), UTF_8))
                ) {
                    while((input = in.readLine()) != null) {
                        sane = handleMessage(decrypt(input));
                        if(sane) scrollBar.setValue(scrollBar.getMaximum());
                        else break;
                    }
                }
                conn.disconnect();
                return sane;
            }

            private boolean handleMessage(String message) throws IOException, BadLocationException, NumberFormatException {
                if(message == null) return false;
                final int header = message.isEmpty() ? -1 : message.codePointAt(0);
                if (header == 21) {
                    if (message.length() == 1) {
                        if (!userNames.isEmpty()) {
                            userName = userNames.remove(random.nextInt(userNames.size()));
                        } else {
                            userName = Integer.toString(36 * 36 * 36 + random.nextInt(35 * 36 * 36 * 36), 36);
                        }
                        enqueue((char) 6 + userName);
                    } else {
                        userName = message.substring(1);
                        stdOut.setLogicalStyle(stdOut.getLength(), serverStyle);
                        stdOut.insertString(stdOut.getLength(), "<< That username is taken >>\n", null);
                    }
                    ((JFrame) chatPane.getTopLevelAncestor()).setTitle("CN Chat: " + userName);
                    return true;
                }
                stdOut.setLogicalStyle(stdOut.getLength(), peerStyle);
                MarkdownUtils.format = message.length() != (message = message.replace(""+(char)17, "")).length();
                if (message.length() != (message = message.replaceAll("[\\x0e\\x0f\\x7f-\\x9f]", "")).length()) {
                    stdOut.setLogicalStyle(stdOut.getLength(), directStyle);
                }
                if (message.length() != (message = message.replaceAll("[\\x00-\\x07\\x10-\\x1f]", "")).length()) {
                    stdOut.setLogicalStyle(stdOut.getLength(), serverStyle);
                    stdOut.insertString(stdOut.getLength(), message + "\n", null);
                    return true;
                }
                final String command = message.toLowerCase();
                if (command.contains(":color ")) {
                    if (rainbow.isAlive()) rainbow.interrupt();
                    if (command.contains("white") || command.contains("reset"))
                        chatPane.setBackground(Color.WHITE);
                    else if (command.contains("rainbow")) {
                        rainbow = new Thread(rainbowRun);
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
                        int[] format = MarkdownUtils.genFormatMap(message);
                        for (int i = 0, cl = stdOut.getLength(); i < format.length; i++, cl++) {
                            if (format[i] == -1) {
                                cl--;
                                continue;
                            }
                            if (format[i] == 0) {
                                stdOut.insertString(cl, "" + message.charAt(i), null);
                                continue;
                            }
                            SimpleAttributeSet fmt = new SimpleAttributeSet();
                            if ((format[i] & 1) != 0) StyleConstants.setItalic(fmt, true);
                            if ((format[i] & 2) != 0) StyleConstants.setBold(fmt, true);
                            if ((format[i] & 4) != 0) StyleConstants.setFontFamily(fmt, "Inconsolata");
                            stdOut.insertString(cl, "" + message.charAt(i), fmt);
                        }
                        stdOut.insertString(stdOut.getLength(), "\n", null);
                    } else {
                        stdOut.insertString(stdOut.getLength(), message+"\n", null);
                    }
                }
                return true;
            }
        }

        refresh();
        conn.setDoOutput(true);
        try(PrintWriter out = new PrintWriter(new OutputStreamWriter(conn.getOutputStream(), UTF_8), true)
        ) {
            long mask = -1L >>> 1;
            String check = String.format("%01x%015x%016x", random.nextInt(8) + 8, random.nextLong() & (mask >> 3), random.nextLong() & mask);
            out.print(check);
            out.close();
        }
        try(BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream(), UTF_8))
        ) {
            uuid = in.readLine();
        }
        conn.disconnect();
        host = new URL(host.getProtocol(), host.getHost(), host.getPort(), "/" + uuid);

        try {
            try {
                synchronized(cipherLock) {
                    ChatCrypt chatCrypt = new ChatCrypt(host);
                    cipherD = chatCrypt.cipherD;
                    cipherE = chatCrypt.cipherE;
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            enqueue((char) 6 + userName);


            new ChatClient().setVisible(true);
            final MessageHandler md = new MessageHandler();
            Runtime.getRuntime().addShutdownHook(new Thread(
                    new Runnable() {
                        @Override
                        public void run() {
                            enqueue((char) 4 + userName);
                            try {
                                md.sendAndReceive();
                            } catch (Throwable ignored) {
                            }
                        }
                    }
            ));

            boolean up = true;
            while(up) {
                try {
                    Thread.sleep(16);
                } catch (InterruptedException ignored) {
                }
                up = md.sendAndReceive();
            }
        } catch(BadLocationException | NumberFormatException e) {
            e.printStackTrace();
        }
    }
}
