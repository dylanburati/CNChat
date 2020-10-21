package com.dylanburati.cnchat.server.ChatUtils;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.*;
import java.util.Arrays;
import java.util.List;
import java.util.Scanner;
import java.util.UUID;
import java.util.stream.Collectors;

import static java.nio.charset.StandardCharsets.UTF_8;

public abstract class DelegateHandler implements HttpHandler {

    @Override
    public void handle(HttpExchange conn) throws IOException {
        String input = "";
        try(BufferedReader in = new BufferedReader(new InputStreamReader(conn.getRequestBody(), UTF_8))
        ) {
            input = in.lines().collect(Collectors.joining("\n"));
        }

        // Allow file:// urls to request authorization if testing
        if("true".equals(System.getProperty("CNChat.testing"))) {
            conn.getResponseHeaders().add("Access-Control-Allow-Origin", "null");
        }
        HttpResponse response = handleRequest(input);
        try(PrintWriter out = new PrintWriter(new OutputStreamWriter(conn.getResponseBody(), UTF_8), true)
        ) {
            conn.sendResponseHeaders(response.code, response.output.length());
            out.print(response.output);
        }
    }

    private HttpResponse handleRequest(String input) {
        if(input.startsWith("join ")) {
            String userName = input.substring(5);
            if(!userName.matches("[0-9A-Za-z-_\\.]+")) {
                System.out.println("Username has illegal characters\n" +
                        "Please make sure your auth server port (defaults to 8081) is not open to the Internet");
                return new HttpResponse(400);
            }

            String uuid = UUID.randomUUID().toString().replace("-", "");
            onJoinRequest(uuid, userName);
            return new HttpResponse(200).withData(uuid);
        }

        if(input.startsWith("register ")) {
            List<String> lines = Arrays.stream(input.substring(9).split("[\\r\\n]+")).filter(s -> !s.isEmpty())
                    .collect(Collectors.toList());
            if(lines.size() != 5) {
                return new HttpResponse(400);
            }
            String userName = lines.get(0);
            if(!userName.matches("[0-9A-Za-z-_\\.]+")) {
                System.out.println("Username has illegal characters\n" +
                        "Please make sure your auth server port (defaults to 8081) is not open to the Internet");
                return new HttpResponse(400);
            }
            JSONStructs.KeySet keySet = new JSONStructs.KeySet(
                    userName, lines.get(1), lines.get(2), lines.get(3), lines.get(4));
            onRegister(keySet);
            return new HttpResponse(200);
        }

        // Not found
        return new HttpResponse(400);
    }

    public abstract void onJoinRequest(String uuid, String userName);

    public abstract void onRegister(JSONStructs.KeySet keySet);
}
