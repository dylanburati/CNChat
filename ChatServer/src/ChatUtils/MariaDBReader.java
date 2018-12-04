package ChatUtils;

import com.jsoniter.JsonIterator;
import com.jsoniter.output.JsonStream;

import java.security.Key;
import java.sql.*;
import java.util.HashMap;
import java.util.Map;

public class MariaDBReader {
    static class KeySet {
        public String user;
        public String identity_public;
        public String identity_private;
        public String prekey_public;
        public String prekey_private;

        public KeySet(String user, String identity_public, String identity_private, String prekey_public, String prekey_private) {
            this.user = user;
            this.identity_public = identity_public;
            this.identity_private = identity_private;
            this.prekey_public = prekey_public;
            this.prekey_private = prekey_private;
        }
    }

    static class KeySet2 {
        public String user;
        public String identity_public;
        public String prekey_public;

        public KeySet2(String user, String identity_public, String prekey_public) {
            this.user = user;
            this.identity_public = identity_public;
            this.prekey_public = prekey_public;
        }
    }

    public static String retrieveKeys(String userName, boolean self) {
        try {
            Class.forName("org.mariadb.jdbc.Driver");
            Connection conn = null;

            String usersSql = null;
            PreparedStatement usersStmt = null;
            try {
                conn = DriverManager.getConnection("jdbc:mariadb://127.0.0.1:3306/testing?user=test&password=");
                if(self) {
                    usersSql = "SELECT identity_public, identity_private, prekey_public, prekey_private FROM $keystore WHERE name = ?";
                } else {
                    usersSql = "SELECT identity_public, prekey_public FROM $keystore WHERE name = ?";
                }
                usersStmt = conn.prepareStatement(usersSql);
                usersStmt.setString(1, userName);
                ResultSet usersResults = usersStmt.executeQuery();
                if(usersResults.next()) {
                    if(self) {
                        KeySet ks = new KeySet(userName, usersResults.getString("identity_public"), usersResults.getString("identity_private"),
                                usersResults.getString("prekey_public"), usersResults.getString("prekey_private"));
                        return JsonStream.serialize(ks);
                    } else {
                        KeySet2 ks2 = new KeySet2(userName, usersResults.getString("identity_public"), usersResults.getString("prekey_public"));
                        return JsonStream.serialize(ks2);
                    }
                }
            } catch(SQLException e) {
                e.printStackTrace();
                System.err.println(e.getMessage());
            } finally {
                if(usersStmt != null) usersStmt.close();
                if(conn != null) conn.close();
            }
            return null;
        } catch(ClassNotFoundException | SQLException e) {
            return null;
        }
    }

}
