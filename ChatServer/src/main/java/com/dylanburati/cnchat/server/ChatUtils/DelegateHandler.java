package com.dylanburati.cnchat.server.ChatUtils;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.*;
import java.util.UUID;

import static java.nio.charset.StandardCharsets.UTF_8;

public abstract class DelegateHandler implements HttpHandler {

    @Override
    public void handle(HttpExchange conn) throws IOException {
        String uuid = null, userName = null, input;
        boolean join = false;
        try(BufferedReader in = new BufferedReader(new InputStreamReader(conn.getRequestBody(), UTF_8))
        ) {
            input = in.readLine();
            if(input != null && input.startsWith("join ")) {
                join = true;
                userName = input.substring(5);
                if(!userName.matches("[0-9A-Za-z-_\\.]+")) {
                    join = false;
                    System.out.println("Username has illegal characters\n" +
                            "Please make sure your auth server port (defaults to 8081) is not open to the Internet");
                }
            }
        }

        // Allow file:// urls to request authorization if testing
        if("true".equals(System.getProperty("CNChat.testing"))) {
            conn.getResponseHeaders().add("Access-Control-Allow-Origin", "null");
        }
        try(PrintWriter out = new PrintWriter(new OutputStreamWriter(conn.getResponseBody(), UTF_8), true)
        ) {
            if(!join) {
                conn.sendResponseHeaders(400, 2);
                out.print("\r\n");
                out.close();
                return;
            }

            uuid = UUID.randomUUID().toString().replace("-", "");
            onJoinRequest(uuid, userName);

            conn.sendResponseHeaders(200, 32);
            out.print(uuid);
        }
    }

    public abstract void onJoinRequest(String uuid, String userName);
}
