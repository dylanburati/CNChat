package ChatUtils;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

import static java.nio.charset.StandardCharsets.UTF_8;

public class CNConversationStore {
    public String fin;
    private volatile List<CNConversation> arr = new ArrayList<>();
    private int nextID = 1;

    public CNConversationStore(String fin) {
        this.fin = fin;
        if(fin != null) {
            try(Scanner scanner = new Scanner(new File(fin))) {
                scanner.useDelimiter("[{}][\\s]*");
                String conv;
                while(scanner.hasNext()) {
                    conv = scanner.next();
                    if(conv.matches("[^\\s]+")) {
                        CNConversation c = new CNConversation(conv);
                        if(c.id >= nextID) nextID = c.id + 1;
                        arr.add(c);
                    }
                }
            } catch(FileNotFoundException e) {
                e.printStackTrace();
            } catch(IOException e) {
                e.printStackTrace();
            }
        }
    }

    public synchronized boolean addConversation(String role1, String role2, List<String> role3,
                                   List<String> ephemeralPubKeysEnc, String role1WrappedKeyEnc) {
        CNConversation c = new CNConversation(nextID, role1, role2, role3, ephemeralPubKeysEnc, role1WrappedKeyEnc);
        arr.add(c);
        nextID++;

        try(PrintWriter out = new PrintWriter(new OutputStreamWriter(new FileOutputStream(fin), UTF_8), true)) {
            out.write("{");
            out.println(c.toTextRecord());
            out.println("}");
        } catch(IOException e) {
            return false;
        }
        return true;
    }
}
