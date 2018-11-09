package ChatUtils;

import java.sql.*;
import java.util.HashMap;
import java.util.Map;

public class MariaDBReader {
    public static String selectUsers(String user, String columnName) {
        try {
            Class.forName("org.mariadb.jdbc.Driver");
            Connection conn = null;

            PreparedStatement usersStmt = null;
            try {
                conn = DriverManager.getConnection("jdbc:mariadb://127.0.0.1:3306/testing?user=test&password=");
                String usersSql = "SELECT name, pass FROM $users WHERE name = ?";
                usersStmt = conn.prepareStatement(usersSql);
                usersStmt.setString(1, user);
                ResultSet usersResults = usersStmt.executeQuery();
                if(!usersResults.next()) {
                    // No results
                    System.err.println("MariaDBReader.selectUsers: no results");
                    return null;
                }
                String retval = usersResults.getString(columnName);
                if(usersResults.next()) {
                    // Ambiguous result, should never happen
                    System.err.println("MariaDBReader.selectUsers: more than 1 result");
                    return null;
                }
                return retval;
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

    public static Map<String, String> selectAllUserIDs() {
        try {
            Class.forName("org.mariadb.jdbc.Driver");
            Connection conn = null;

            PreparedStatement usersStmt = null;
            try {
                conn = DriverManager.getConnection("jdbc:mariadb://127.0.0.1:3306/testing?user=test&password=");
                String usersSql = "SELECT name, chat_id FROM $users";
                usersStmt = conn.prepareStatement(usersSql);
                ResultSet usersResults = usersStmt.executeQuery();
                Map<String, String> retval = new HashMap<>();
                while(usersResults.next()) {
                    retval.put(usersResults.getString("chat_id"), usersResults.getString("name"));
                }
                return retval;
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

    public static int updateUserID(String user, String uuid) {
        try {
            Class.forName("org.mariadb.jdbc.Driver");
            Connection conn = null;

            PreparedStatement usersStmt = null;
            try {
                conn = DriverManager.getConnection("jdbc:mariadb://127.0.0.1:3306/testing?user=test&password=");
                String usersSql = "UPDATE $users SET chat_id = ? WHERE name = ?";
                usersStmt = conn.prepareStatement(usersSql);
                usersStmt.setString(1, uuid);
                usersStmt.setString(2, user);
                return usersStmt.executeUpdate();
            } catch(SQLException e) {
                e.printStackTrace();
                System.err.println(e.getMessage());
            } finally {
                if(usersStmt != null) usersStmt.close();
                if(conn != null) conn.close();
            }
            return -1;
        } catch(ClassNotFoundException | SQLException e) {
            return -1;
        }
    }
}
