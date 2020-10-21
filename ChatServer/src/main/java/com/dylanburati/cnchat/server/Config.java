package com.dylanburati.cnchat.server;

public class Config {
    public final int AUTH_PORT;
    public final int WS_PORT;
    public final String MYSQL_HOST;
    public final int MYSQL_PORT;
    public final String MYSQL_USER;
    public final String MYSQL_PASSWORD;
    public final String MYSQL_DATABASE;

    private Config(int auth_port, int ws_port, String mysql_host, int mysql_port, String mysql_username, String mysql_password,
                   String mysql_database) {
        if (auth_port <= 0 || auth_port > 65535) {
            throw new IllegalStateException("Invalid auth port");
        }
        if (ws_port <= 0 || ws_port > 65535) {
            throw new IllegalStateException("Invalid websocket port");
        }
        if (mysql_host == null || mysql_host.isEmpty()) {
            throw new IllegalStateException("Invalid mysql host");
        }
        if (mysql_port <= 0 || mysql_port > 65535) {
            throw new IllegalStateException("Invalid mysql port");
        }
        if (mysql_username == null || mysql_username.isEmpty()) {
            throw new IllegalStateException("Invalid mysql username");
        }
        if (mysql_password == null || mysql_password.isEmpty()) {
            throw new IllegalStateException("Invalid mysql password");
        }
        if (mysql_database == null || mysql_database.isEmpty()) {
            throw new IllegalStateException("Invalid mysql database name");
        }

        AUTH_PORT = auth_port;
        WS_PORT = ws_port;
        MYSQL_HOST = mysql_host;
        MYSQL_PORT = mysql_port;
        MYSQL_USER = mysql_username;
        MYSQL_PASSWORD = mysql_password;
        MYSQL_DATABASE = mysql_database;
    }

    private static int envIntOrDefault(String name, int dfault) {
        return Integer.getInteger(System.getenv(name), dfault);
    }

    private static String envStringOrDefault(String name, String dfault) {
        String envStr = System.getenv(name);
        return envStr != null ? envStr : dfault;
    }

    public static Config loadFromEnv() {
        return new Config(envIntOrDefault("AUTH_PORT", 8081),
                envIntOrDefault("WS_PORT", 8082),
                envStringOrDefault("MYSQL_HOST", "localhost"),
                envIntOrDefault("MYSQL_PORT", 3306),
                System.getenv("MYSQL_USER"),
                System.getenv("MYSQL_PASSWORD"),
                envStringOrDefault("MYSQL_DATABASE", "cnchat"));
    }
}
