package ChatUtils;

import java.sql.*;

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
}
