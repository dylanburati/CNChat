import javax.swing.*;
import javax.swing.text.BadLocationException;
import javax.swing.text.StyledDocument;
import java.awt.*;

public class UnicodeCheck {
    public static void main(String[] args) throws BadLocationException {
        JFrame frame = new JFrame();
        frame.setLayout(new FlowLayout(FlowLayout.CENTER));
        frame.setSize(280, 420);
        JTextPane chatPane = new JTextPane();
        chatPane.setPreferredSize(new Dimension(260, 390));
        chatPane.setMinimumSize(new Dimension(260, 390));
        chatPane.setMaximumSize(new Dimension(260, 390));
        chatPane.setEditable(false);
        StyledDocument stdOut = chatPane.getStyledDocument();
        JScrollPane scrollPane = new JScrollPane(chatPane);
        frame.add(scrollPane);
        frame.setVisible(true);
		StringBuilder codepage = new StringBuilder();
        for (int i = 0; i <= 0xFFFF; i++) {
			codepage.append(String.format("%1$5d\t%1$04x\t'%1$c'\n", i));
        }
		stdOut.insertString(stdOut.getLength(), codepage.toString(), null);
		scrollPane.getVerticalScrollBar().setValue(scrollPane.getVerticalScrollBar().getMaximum());
    }
}