package com.dylanburati.cnchat.server.ChatUtils;

import com.dylanburati.cnchat.server.Config;
import com.jsoniter.output.JsonStream;

import java.io.*;
import java.sql.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static java.nio.charset.StandardCharsets.UTF_8;

public class MariaDBReader {
    public static String keystoreTableName = "$keystore";
    public static String messagesTableName = "$cnchat_messages";
    public static String conversationsTableName = "$cnchat_conversations";
    public static String conversationsUsersTableName = "$cnchat_conversations_users";

    private final String dbConnectionString;

    public MariaDBReader(Config config) {
        this.dbConnectionString = MariaDBSchemaManager.getDbConnectionString(config);
    }
    
    public String retrieveKeysSelf(String userName) {
        try {
            Connection conn = null;

            PreparedStatement usersStmt = null;
            ResultSet usersResults = null;
            try {
                conn = DriverManager.getConnection(dbConnectionString);
                String usersSql = String.format(
                        "SELECT identity_public, identity_private, prekey_public, prekey_private FROM %s WHERE name = ?", keystoreTableName);
                usersStmt = conn.prepareStatement(usersSql);
                usersStmt.setString(1, userName);
                usersResults = usersStmt.executeQuery();
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
                if(usersResults != null) usersResults.close();
                if(conn != null) conn.close();
            }
            return null;
        } catch(SQLException e) {
            e.printStackTrace();
            return null;
        }
    }

    public String retrieveKeysOther(String otherUserName, String selfUserName) {
        try {
            Connection conn = null;

            PreparedStatement usersStmt = null;
            ResultSet usersResults = null;
            try {
                conn = DriverManager.getConnection(dbConnectionString);
                String usersSql = String.format("SELECT name, identity_public, prekey_public FROM %s WHERE name = ?", keystoreTableName);
                usersStmt = conn.prepareStatement(usersSql);
                usersStmt.setString(1, otherUserName);
                usersResults = usersStmt.executeQuery();
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
                if(usersResults != null) usersResults.close();
                if(conn != null) conn.close();
            }
            return null;
        } catch(SQLException e) {
            e.printStackTrace();
            return null;
        }
    }

    public List<String> getMessages(int cID, int nBack) {
        if(cID <= 0 || nBack <= 0) {
            return null;
        }
        try {
            Connection conn = null;

            PreparedStatement msgStmt = null;
            ResultSet msgSet = null;
            try {
                conn = DriverManager.getConnection(dbConnectionString);
                String msgSql = String.format("SELECT data FROM %s WHERE id = ? ORDER BY n DESC LIMIT ?", messagesTableName);

                msgStmt = conn.prepareStatement(msgSql);
                msgStmt.setInt(1, cID);
                msgStmt.setInt(2, nBack);
                msgSet = msgStmt.executeQuery();
                List<String> messages = new ArrayList<>();

                while(msgSet.next()) {
                    StringBuilder mb = new StringBuilder();
                    try(BufferedReader in = new BufferedReader(new InputStreamReader(msgSet.getBinaryStream("data"), UTF_8))) {
                        String line;
                        while((line = in.readLine()) != null) {
                            mb.append(line).append("\n");
                        }
                    }
                    if(mb.length() > 1) mb.setLength(mb.length() - 1);  // trim extra line break
                    messages.add(mb.toString());
                }
                return messages;
            } catch(SQLException | IOException e) {
                e.printStackTrace();
            } finally {
                if(msgStmt != null) msgStmt.close();
                if(msgSet != null) msgSet.close();
                if(conn != null) conn.close();
            }
            return null;
        } catch(SQLException e) {
            e.printStackTrace();
            return null;
        }
    }

    public void updateMessageStore(Integer cID, String msg) {
        if(cID <= 0 || msg.isEmpty()) return;
        try {
            Connection conn = null;

            PreparedStatement writeStmt = null;
            try {
                conn = DriverManager.getConnection(dbConnectionString);
                String writeSql = String.format("INSERT INTO %s (id, data) VALUES (?, ?)", messagesTableName);

                writeStmt = conn.prepareStatement(writeSql);
                writeStmt.setInt(1, cID);
                writeStmt.setBlob(2, new ByteArrayInputStream(msg.getBytes(UTF_8)));
                writeStmt.executeUpdate();
            } catch(SQLException e) {
                e.printStackTrace();
            } finally {
                if(writeStmt != null) writeStmt.close();
                if(conn != null) conn.close();
            }
        } catch(SQLException e) {
            e.printStackTrace();
        }
    }

    public Map<Integer, JSONStructs.Conversation> getConversationStore() {
        try {
            Connection conn = null;

            PreparedStatement convStmt = null;
            ResultSet convSet = null;
            PreparedStatement conv2Stmt = null;
            ResultSet conv2Set = null;
            try {
                conn = DriverManager.getConnection(dbConnectionString);
                String convSql = String.format("SELECT id, exchange_complete, crypt_expiration FROM %s", conversationsTableName);

                convStmt = conn.prepareStatement(convSql);
                convSet = convStmt.executeQuery();
                Map<Integer, JSONStructs.Conversation> conversations = new HashMap<>();
                while(convSet.next()) {
                    JSONStructs.Conversation c = new JSONStructs.Conversation();
                    c.id = convSet.getInt("id");
                    c.exchange_complete = convSet.getBoolean("exchange_complete");
                    c.crypt_expiration = convSet.getLong("crypt_expiration");
                    conversations.put(c.id, c);
                }

                String conv2Sql = String.format("SELECT id, role, user, key_ephemeral_public, initial_message, key_wrapped FROM %s", conversationsUsersTableName);
                conv2Stmt = conn.prepareStatement(conv2Sql);
                conv2Set = conv2Stmt.executeQuery();
                while(conv2Set.next()) {
                    JSONStructs.Conversation c = conversations.get(conv2Set.getInt("id"));
                    if(c == null) {
                        continue;
                    }
                    JSONStructs.ConversationUser u = new JSONStructs.ConversationUser(conv2Set.getString("user"),
                            conv2Set.getInt("role"), conv2Set.getString("key_ephemeral_public"),
                            conv2Set.getString("key_wrapped"), conv2Set.getString("initial_message"));
                    c.users.add(u);
                }

                conversations.entrySet().removeIf(e -> {
                   if(!e.getValue().validate()) {
                       System.out.format("Invalid conversation found, id = %d\n", e.getKey());
                       return true;
                   }
                   return false;
                });
                return conversations;
            } catch(SQLException e) {
                e.printStackTrace();
            } finally {
                if(convStmt != null) convStmt.close();
                if(convSet != null) convSet.close();
                if(conv2Stmt != null) conv2Stmt.close();
                if(conv2Set != null) conv2Set.close();
                if(conn != null) conn.close();
            }
            return null;
        } catch(SQLException e) {
            e.printStackTrace();
            return null;
        }
    }

    public void updateConversationStore(JSONStructs.Conversation c) {
        try {
            Connection conn = null;

            PreparedStatement writeStmt = null;
            PreparedStatement write2Stmt = null;
            try {
                conn = DriverManager.getConnection(dbConnectionString);
                String writeSql = String.format("INSERT INTO %s (id, exchange_complete, crypt_expiration) VALUES (?, ?, ?) " +
                        "ON DUPLICATE KEY UPDATE id = ?, exchange_complete = ?, crypt_expiration = ?", conversationsTableName);

                writeStmt = conn.prepareStatement(writeSql);
                writeStmt.setInt(1, c.id);
                writeStmt.setBoolean(2, c.exchange_complete);
                writeStmt.setLong(3, c.crypt_expiration);
                writeStmt.setInt(4, c.id);
                writeStmt.setBoolean(5, c.exchange_complete);
                writeStmt.setLong(6, c.crypt_expiration);
                writeStmt.executeUpdate();

                conn.setAutoCommit(false);
                String write2Sql = String.format("INSERT INTO %s (id, role, user, key_ephemeral_public, initial_message, key_wrapped) " +
                        "VALUES (?, ?, ?, ?, ?, ?) " +
                        "ON DUPLICATE KEY UPDATE id = ?, role = ?, user = ?, " +
                            "key_ephemeral_public = ?, initial_message = ?, key_wrapped = ?", conversationsUsersTableName);
                write2Stmt = conn.prepareStatement(write2Sql);
                for(JSONStructs.ConversationUser u : c.users) {
                    write2Stmt.setInt(1, c.id);
                    write2Stmt.setInt(2, u.role);
                    write2Stmt.setString(3, u.user);
                    write2Stmt.setString(4, u.key_ephemeral_public);
                    write2Stmt.setString(5, u.initial_message);
                    write2Stmt.setString(6, u.key_wrapped);
                    write2Stmt.setInt(7, c.id);
                    write2Stmt.setInt(8, u.role);
                    write2Stmt.setString(9, u.user);
                    write2Stmt.setString(10, u.key_ephemeral_public);
                    write2Stmt.setString(11, u.initial_message);
                    write2Stmt.setString(12, u.key_wrapped);
                    write2Stmt.executeUpdate();
                }
                conn.commit();
                conn.setAutoCommit(true);
            } catch(SQLException e) {
                e.printStackTrace();
            } finally {
                if(writeStmt != null) writeStmt.close();
                if(write2Stmt != null) write2Stmt.close();
                if(conn != null) conn.close();
            }
        } catch(SQLException e) {
            e.printStackTrace();
        }
    }
}
