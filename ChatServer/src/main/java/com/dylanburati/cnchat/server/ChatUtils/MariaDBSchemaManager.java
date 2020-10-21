package com.dylanburati.cnchat.server.ChatUtils;

import com.dylanburati.cnchat.server.Config;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;

public class MariaDBSchemaManager {
    public static String keystoreTableName = "$keystore";
    public static String messagesTableName = "$cnchat_messages";
    public static String conversationsTableName = "$cnchat_conversations";
    public static String conversationsUsersTableName = "$cnchat_conversations_users";

    private final String dbConnectionString;

    public MariaDBSchemaManager(Config config) {
        this.dbConnectionString = getDbConnectionString(config);
    }

    static String getDbConnectionString(Config config) {
        return String.format("jdbc:mysql://%s:%d/%s?user=%s&password=%s",
                config.MYSQL_HOST, config.MYSQL_PORT, config.MYSQL_DATABASE, config.MYSQL_USER, config.MYSQL_PASSWORD);
    }

    // Each row is the message's conversation id and the UTF-8 decoded representation
    // of the message string "conversationID;from;time;classes;iv;hmac;message_encrypted"
    private void createMessagesTable() {
        try {
            Connection conn = null;

            PreparedStatement createStmt = null;
            try {
                conn = DriverManager.getConnection(dbConnectionString);
                String createSql = String.format("CREATE TABLE IF NOT EXISTS %s (" +
                        "n INT NOT NULL AUTO_INCREMENT, " +
                        "id INT NOT NULL, " +
                        "data LONGBLOB NOT NULL, " +
                        "PRIMARY KEY (n)" +
                        ")", messagesTableName);

                createStmt = conn.prepareStatement(createSql);
                createStmt.execute();
            } finally {
                if(createStmt != null) createStmt.close();
                if(conn != null) conn.close();
            }
        } catch(SQLException e) {
            throw new IllegalStateException("Could not create messages table", e);
        }
    }

    // Each row is equivalent to a JSONStructs.Conversation object, minus the field `users`
    private void createConversationsTable() {
        try {
            Connection conn = null;

            PreparedStatement createStmt = null;
            try {
                conn = DriverManager.getConnection(dbConnectionString);
                String createSql = String.format("CREATE TABLE IF NOT EXISTS %s (" +
                        "id INT NOT NULL, " +
                        "exchange_complete BOOLEAN NOT NULL, " +
                        "crypt_expiration BIGINT NOT NULL, " +
                        "PRIMARY KEY (id), " +
                        "CHECK (id > 0)" +
                        ")", conversationsTableName);

                createStmt = conn.prepareStatement(createSql);
                createStmt.execute();
            } finally {
                if(createStmt != null) createStmt.close();
                if(conn != null) conn.close();
            }
        } catch(SQLException e) {
            throw new IllegalStateException("Could not create conversations table", e);
        }
    }

    // Each row is equivalent to a JSONStructs.ConversationUser object
    private void createConversationsUsersTable() {
        try {
            Connection conn = null;

            PreparedStatement createStmt = null;
            try {
                conn = DriverManager.getConnection(dbConnectionString);
                String createSql = String.format("CREATE TABLE IF NOT EXISTS %s (" +
                        "id INT NOT NULL, " +
                        "role INT NOT NULL, " +
                        "user VARCHAR(64) NOT NULL, " +
                        "key_ephemeral_public VARCHAR(768), " +
                        "initial_message VARCHAR(768), " +
                        "key_wrapped VARCHAR(256), " +
                        "PRIMARY KEY (id, user), " +
                        "CHECK (id > 0 AND (role = 1 OR role = 2))" +
                        ")", conversationsUsersTableName);

                createStmt = conn.prepareStatement(createSql);
                createStmt.execute();
            } finally {
                if(createStmt != null) createStmt.close();
                if(conn != null) conn.close();
            }
        } catch(SQLException e) {
            throw new IllegalStateException("Could not create conversationsUsers table", e);
        }
    }

    // Each row is equivalent to a JSONStructs.KeySet object
    private void createKeystoreTable() {
        try {
            Connection conn = null;

            PreparedStatement createStmt = null;
            try {
                conn = DriverManager.getConnection(dbConnectionString);
                String createSql = String.format("CREATE TABLE IF NOT EXISTS %s (" +
                        "name VARCHAR(64) NOT NULL, " +
                        "identity_public VARCHAR(768) NOT NULL, " +
                        "identity_private VARCHAR(256) NOT NULL, " +
                        "prekey_public VARCHAR(768) NOT NULL, " +
                        "prekey_private VARCHAR(256) NOT NULL, " +
                        "PRIMARY KEY (`name`)" +
                        ")", keystoreTableName);

                createStmt = conn.prepareStatement(createSql);
                createStmt.execute();
            } finally {
                if(createStmt != null) createStmt.close();
                if(conn != null) conn.close();
            }
        } catch(SQLException e) {
            throw new IllegalStateException("Could not create keystore table", e);
        }
    }

    public void createTables() {
        createMessagesTable();
        createConversationsTable();
        createConversationsUsersTable();
        createKeystoreTable();
    }
}
