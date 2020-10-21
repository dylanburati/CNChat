package com.dylanburati.cnchat.server.ChatUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

class HttpResponse {
    public final int code;
    public final StringBuilder output = new StringBuilder();
    private final Map<String, String> headers = new HashMap<>();

    public HttpResponse(int code) {
        this.code = code;
        this.output.append("\r\n");
    }

    public HttpResponse withData(String data) {
        output.setLength(0);
        output.append(data);
        return this;
    }

    public HttpResponse withHeader(String key, String value) {
        this.headers.put(key, value);
        return this;
    }

    public List<String> getHeaderLines() {
        return headers.entrySet().stream()
                .map(e -> String.format("%s: %s", e.getKey(), e.getValue()))
                .collect(Collectors.toList());
    }

    public String getStatusLine() {
        switch (this.code) {
            case 101: return "HTTP/1.1 101 Switching Protocols";
            case 200: return "HTTP/1.1 200 OK";
            case 400: return "HTTP/1.1 400 Bad Request";
            default: return String.format("HTTP/1.1 %d Unknown Status", this.code);
        }
    }
}
