package ChatUtils;

import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.SecretKey;
import javax.crypto.spec.DHParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.xml.bind.DatatypeConverter;
import java.io.*;
import java.math.BigInteger;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static ChatUtils.AuthUtils.crypt64decode;
import static java.nio.charset.StandardCharsets.UTF_8;

public class ChatCryptResume {

    private static final BigInteger otr1536Modulus = new BigInteger(
            "FFFFFFFFFFFFFFFFC90FDAA22168C234" +
                    "C4C6628B80DC1CD129024E088A67CC74" +
                    "020BBEA63B139B22514A08798E3404DD" +
                    "EF9519B3CD3A431B302B0A6DF25F1437" +
                    "4FE1356D6D51C245E485B576625E7EC6" +
                    "F44C42E9A637ED6B0BFF5CB6F406B7ED" +
                    "EE386BFB5A899FA5AE9F24117C4B1FE6" +
                    "49286651ECE45B3DC2007CB8A163BF05" +
                    "98DA48361C55D39A69163FA8FD24CF5F" +
                    "83655D23DCA3AD961C62F356208552BB" +
                    "9ED529077096966D670C354E4ABC9804" +
                    "F1746C08CA237327FFFFFFFFFFFFFFFF", 16);

    private static final BigInteger otr1536Base = BigInteger.valueOf(2);
    private final String algo;
    private final String wrappedKeyPath;
    private final String user;

    private List<byte[]> inQueue = new ArrayList<>();
    private final Object inQueueLock = new Object();
    private List<byte[]> outQueue = new ArrayList<>();
    private final Object outQueueLock = new Object();

    public Cipher cipherD = null;
    public Cipher cipherE = null;
    public byte[] privateKey;

    private static class Self {
        private KeyPairGenerator keyPairGen;
        private KeyPair keyPair;
        private KeyAgreement keyAgree;
        private byte[] pubKeyEnc;
        private KeyFactory keyFactory;
        private X509EncodedKeySpec keySpec;
        private byte[] key;
        private byte[] ephemeralKey;
        private byte[] cipherParamsEnc;
        private AlgorithmParameters cipherParams;
        private byte[] wrappedKey;
        private byte[] keyUnwrapper;
        private SecretKey keyAES;
    }

    private static class Party2 {
        private byte[] pubKeyEnc;
        private PublicKey pubKey;
    }

    private Self self = new Self();
    private Party2 party2 = new Party2();

    private class CryptHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange conn) throws IOException {
            try(BufferedReader in = new BufferedReader(new InputStreamReader(conn.getRequestBody(), UTF_8))
            ) {
                byte[] input = DatatypeConverter.parseBase64Binary(in.readLine());
                synchronized(inQueueLock) {
                    inQueue.add(input);
                }
            }

            try {
                runStage(inQueue.size());
            } catch(Exception e) {
                e.printStackTrace();
            }

            try(PrintWriter out = new PrintWriter(new OutputStreamWriter(conn.getResponseBody(), UTF_8), true)
            ) {
                String data;
                synchronized(outQueueLock) {
                    data = DatatypeConverter.printBase64Binary(outQueue.remove(0));
                }
                conn.sendResponseHeaders(200, data.length());  // base64 ensures data.length() == data.getBytes(UTF_8).length
                out.print(data);
                out.close();
            }
        }
    }

    private void runStage(int stage) throws Exception {
        switch(stage) {
            case 0:
                synchronized(inQueueLock) {
                    synchronized(outQueueLock) {
                        try(BufferedReader keyReader = new BufferedReader(new InputStreamReader(new FileInputStream(wrappedKeyPath), UTF_8))) {
                            String line = keyReader.readLine();
                            if(line == null || line.isEmpty()) {
                                throw new Exception("Wrapped key retrieval failed");
                            }
                            if(line.startsWith("<key>")) {
                                int endData = line.indexOf("</key>");
                                if(endData == -1) {
                                    throw new Exception("Wrapped key retrieval failed");
                                }
                                String wrappedKeyEnc = line.substring(5, endData);
                                if(wrappedKeyEnc.length() != 32) {
                                    throw new Exception("Wrapped key retrieval failed");
                                }
                                self.wrappedKey = new byte[16];
                                for(int i = 0; i < 32; i += 2) {
                                    self.wrappedKey[i / 2] = (byte) Integer.parseInt(wrappedKeyEnc.substring(i, i + 2), 16);
                                }
                            }
                        }

                        self.keyPairGen = KeyPairGenerator.getInstance("DH");
                        self.keyPairGen.initialize(new DHParameterSpec(otr1536Modulus, otr1536Base));
                        self.keyPair = self.keyPairGen.generateKeyPair();
                        self.keyAgree = KeyAgreement.getInstance("DH");
                        self.keyAgree.init(self.keyPair.getPrivate());
                        self.pubKeyEnc = self.keyPair.getPublic().getEncoded();
                        outQueue.add(self.pubKeyEnc);
                    }
                }
                break;
            case 1:
                synchronized(outQueueLock) {
                    synchronized(inQueueLock) {
                        party2.pubKeyEnc = inQueue.get(0);
                    }
                    MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
                    self.keyFactory = KeyFactory.getInstance("DH");
                    self.keySpec = new X509EncodedKeySpec(party2.pubKeyEnc);
                    party2.pubKey = self.keyFactory.generatePublic(self.keySpec);

                    self.keyAgree.doPhase(party2.pubKey, true);
                    self.key = self.keyAgree.generateSecret();
                    self.ephemeralKey = Arrays.copyOf(sha256.digest(self.key), 16);

                    String passwordHashEnc = MariaDBReader.selectUsers(user, "pass");
                    boolean correctEnc = true;
                    for(int i = 0; i < 3 && correctEnc; i++) {
                        switch(i) {
                            case 0:
                                if(passwordHashEnc == null || !(passwordHashEnc.startsWith("$2y$")))
                                    correctEnc = false;
                                else
                                    passwordHashEnc = passwordHashEnc.substring(4);
                                break;
                            case 1:
                                int endCostParam = passwordHashEnc.indexOf("$");
                                if(endCostParam == -1 || endCostParam >= passwordHashEnc.length() - 1)
                                    correctEnc = false;
                                else
                                    passwordHashEnc = passwordHashEnc.substring(endCostParam + 1);
                                break;
                            case 2:
                                if(passwordHashEnc.length() != 53)
                                    correctEnc = false;
                                else
                                    passwordHashEnc = passwordHashEnc.substring(22);
                                break;
                        }
                    }
                    if(!correctEnc) throw new Exception("SQL failed");
                    self.keyUnwrapper = crypt64decode(passwordHashEnc);

                    byte[] ephemeralWithPrivate = new byte[16];
                    privateKey = new byte[16];
                    for(int i = 0; i < 16; i++) {
                        privateKey[i] = (byte) ((self.wrappedKey[i] & 0xFF) ^ (self.keyUnwrapper[i] & 0xFF));
                    }
                    self.keyAES = new SecretKeySpec(privateKey, "AES");
                    cipherD = Cipher.getInstance(algo);
                    cipherE = Cipher.getInstance(algo);

                    for(int i = 0; i < 16; i++) {
                        ephemeralWithPrivate[i] = (byte) ((privateKey[i] & 0xFF) ^ (self.ephemeralKey[i] & 0xFF));
                    }
                    outQueue.add(ephemeralWithPrivate);
                }
                break;
            case 2:
                synchronized(inQueueLock) {
                    self.cipherParamsEnc = inQueue.get(1);
                }
                self.cipherParams = AlgorithmParameters.getInstance("AES");
                self.cipherParams.init(self.cipherParamsEnc);

                cipherE.init(Cipher.ENCRYPT_MODE, self.keyAES, self.cipherParams);
                cipherD.init(Cipher.DECRYPT_MODE, self.keyAES, self.cipherParams);
                synchronized(ChatCryptResume.this) {
                    ChatCryptResume.this.notifyAll();
                }
                break;
        }
    }

    public ChatCryptResume(HttpServer server, String uuid, String user, String algo, String keyPath, Object cryptHandlerLock) throws Exception {
        HttpContext hc;
        synchronized(cryptHandlerLock) {
            this.user = user;
            this.algo = algo;
            this.wrappedKeyPath = keyPath;
            runStage(0);
            hc = server.createContext("/" + uuid, new CryptHandler());
        }
        synchronized(this) {
            while(cipherE == null || cipherD == null || cipherE.getIV() == null || cipherD.getIV() == null) {
                wait();
            }
        }
        server.removeContext(hc);
    }

}
