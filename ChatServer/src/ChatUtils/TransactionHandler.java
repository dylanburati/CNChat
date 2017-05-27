package ChatUtils;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.*;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;

public class TransactionHandler implements HttpHandler {

    private List<String> inQueue;
    private final Object inQueueLock;
    private List<String> outQueue;
    private final Object outQueueLock;

    public TransactionHandler(List<String> inQueue, Object inQueueLock,
                              List<String> outQueue, Object outQueueLock) {
        this.inQueue = inQueue;
        this.inQueueLock = inQueueLock;
        this.outQueue = outQueue;
        this.outQueueLock = outQueueLock;
    }

    @Override
    public void handle(HttpExchange conn) throws IOException {
        try(BufferedReader in = new BufferedReader(new InputStreamReader(conn.getRequestBody(), UTF_8))
        ) {
            String input;
            while((input = in.readLine()) != null) {
                synchronized(inQueueLock) {
                    inQueue.add(input);
                }
            }
        }

        try(PrintWriter out = new PrintWriter(new OutputStreamWriter(conn.getResponseBody(), UTF_8), true)
        ) {
            StringBuilder dataBuilder = new StringBuilder();
            synchronized(outQueueLock) {
                while(outQueue.size() > 0) {
                    dataBuilder.append(outQueue.remove(0));
                    if(outQueue.size() > 0) dataBuilder.append("\r\n");
                }
            }
            String data = dataBuilder.toString();
            conn.sendResponseHeaders(200, data.length());  // base64 ensures data.length() == data.getBytes(UTF_8).length
            out.print(data);
            out.close();
        }
    }
}
