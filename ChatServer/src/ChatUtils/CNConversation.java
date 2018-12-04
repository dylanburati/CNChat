package ChatUtils;

import javax.xml.bind.DatatypeConverter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

public class CNConversation {
    // never null
    public boolean exchangeComplete;
    public int id;
    public List<String> users = new ArrayList<>();
    public List<Integer> userRoles = new ArrayList<>();

    // null after exchange protocol
    public Long cryptExpiration = null;

    // elements will be null after exchange protocol
    public List<byte[]> ephemeralPubKeys = new ArrayList<>();

    // elements will be null during exchange protocol
    public List<byte[]> wrappedKeys = new ArrayList<>();

    public CNConversation(String textRecord) throws IOException {
        String[] lines = textRecord.split("\n");
        if(lines.length < 3) {
            throw new IOException("textRecord is not long enough");
        }

        //ID:
        this.id = Integer.parseInt(lines[0].substring(3));

        this.exchangeComplete = !(lines[1].startsWith("Expiration:"));
        if(!exchangeComplete) {
            //Expiration:
            this.cryptExpiration = Long.parseLong(lines[1].substring(11));
        }

        for(int i = (exchangeComplete ? 1 : 2); i < lines.length; i++) {
            String[] fields = lines[i].split(";");
            if(fields.length != 4) {
                throw new IOException("textRecord is not long enough");
            }
            userRoles.add(Integer.parseInt(fields[0]));
            users.add(fields[1]);
            if(fields[2].isEmpty()) {
                ephemeralPubKeys.add(null);
            } else {
                ephemeralPubKeys.add(DatatypeConverter.parseBase64Binary(fields[3]));
            }
            if(fields[3].isEmpty()) {
                wrappedKeys.add(null);
            } else {
                wrappedKeys.add(DatatypeConverter.parseBase64Binary(fields[3]));
            }
        }
    }

    public CNConversation(int id, String role1, String role2, List<String> role3,
                          List<String> ephemeralPubKeysEnc, String role1WrappedKeyEnc) {
        if(role3 != null) {
            if(ephemeralPubKeysEnc.size() != role3.size() + 1) {
                throw new RuntimeException("Wrong number of ephemeral keys provided");
            }
        } else if(ephemeralPubKeysEnc.size() != 1) {
            throw new RuntimeException("Wrong number of ephemeral keys provided");
        }

        this.id = id;
        this.exchangeComplete = false;
        this.cryptExpiration = System.currentTimeMillis() + (7 * 24 * 3600 * 1000);

        this.userRoles.add(1);
        this.users.add(role1);
        this.ephemeralPubKeys.add(null);
        this.wrappedKeys.add(DatatypeConverter.parseBase64Binary(role1WrappedKeyEnc));

        this.userRoles.add(2);
        this.users.add(role2);
        this.ephemeralPubKeys.add(DatatypeConverter.parseBase64Binary(ephemeralPubKeysEnc.get(0)));
        this.wrappedKeys.add(null);

        for(int i = 0; role3 != null && i < role3.size(); i++) {
            this.userRoles.add(3);
            this.users.add(role3.get(i));
            this.ephemeralPubKeys.add(DatatypeConverter.parseBase64Binary(ephemeralPubKeysEnc.get(i + 1)));
            this.wrappedKeys.add(null);
        }
    }

    public String toTextRecord() {
        StringBuilder retval = new StringBuilder();
        retval.append("ID:").append(this.id).append("\n");
        if(!this.exchangeComplete) {
            retval.append("Expiration:").append(this.cryptExpiration).append("\n");
        }
        for(int i = 0; i < users.size(); i++) {
            retval.append(userRoles.get(i)).append(";");
            retval.append(users.get(i)).append(";");

            byte[] eph = ephemeralPubKeys.get(i);
            if(eph != null) {
                retval.append(DatatypeConverter.printBase64Binary(eph));
            }
            retval.append(";");

            byte[] wrk = wrappedKeys.get(i);
            if(wrk != null) {
                retval.append(DatatypeConverter.printBase64Binary(wrk));
            }

            if(i < (users.size() - 1)) retval.append("\n");
        }

        return retval.toString();
    }
}
