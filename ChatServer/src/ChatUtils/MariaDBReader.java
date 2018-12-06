package ChatUtils;

import com.jsoniter.JsonIterator;
import com.jsoniter.output.JsonStream;

import java.security.Key;
import java.sql.*;
import java.util.HashMap;
import java.util.Map;

public class MariaDBReader {
    public static String retrieveKeysSelf(String userName) {
        try {
            Class.forName("org.mariadb.jdbc.Driver");
            Connection conn = null;

            String usersSql = null;
            PreparedStatement usersStmt = null;
            try {
                conn = DriverManager.getConnection("jdbc:mariadb://127.0.0.1:3306/testing?user=test&password=");
                usersSql = "SELECT identity_public, identity_private, prekey_public, prekey_private FROM $keystore WHERE name = ?";
                usersStmt = conn.prepareStatement(usersSql);
                usersStmt.setString(1, userName);
                ResultSet usersResults = usersStmt.executeQuery();
                if(usersResults.next()) {
                        JSONStructs.KeySet ks = new JSONStructs.KeySet(userName,
                                usersResults.getString("identity_public"), usersResults.getString("identity_private"),
                                usersResults.getString("prekey_public"), usersResults.getString("prekey_private"));
                        return JsonStream.serialize(ks);
                }
            } catch(SQLException e) {
                e.printStackTrace();
            } finally {
                if(usersStmt != null) usersStmt.close();
                if(conn != null) conn.close();
            }
            return null;
        } catch(ClassNotFoundException | SQLException e) {
            return null;
        }
    }

    public static String retrieveKeysOther(String otherUserName, String selfUserName) {
        try {
            Class.forName("org.mariadb.jdbc.Driver");
            Connection conn = null;

            String usersSql = null;
            PreparedStatement usersStmt = null;
            try {
                conn = DriverManager.getConnection("jdbc:mariadb://127.0.0.1:3306/testing?user=test&password=");
                usersSql = "SELECT name, identity_public, prekey_public FROM $keystore WHERE name = ?";
                usersStmt = conn.prepareStatement(usersSql);
                usersStmt.setString(1, otherUserName);
                ResultSet usersResults = usersStmt.executeQuery();
                if(usersResults.next()) {
                    JSONStructs.KeySet2 ks2 = new JSONStructs.KeySet2(usersResults.getString("name"),
                            usersResults.getString("identity_public"),
                            usersResults.getString("prekey_public"));
                    return JsonStream.serialize(ks2);
                }
            } catch(SQLException e) {
                e.printStackTrace();
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
