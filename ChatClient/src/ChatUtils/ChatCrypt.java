package ChatUtils;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.SecretKey;
import javax.crypto.spec.DHParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;

import static ChatUtils.Codecs.base64decode;
import static ChatUtils.Codecs.base64encode;
import static java.nio.charset.StandardCharsets.UTF_8;

public class ChatCrypt {

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

    private final URL host;
    private HttpURLConnection conn;

    public Cipher cipherD;
    public Cipher cipherE;

    private static class Self {
        private KeyPairGenerator keyPairGen;
        private KeyPair keyPair;
        private KeyAgreement keyAgree;
        private byte[] pubKeyEnc;
        private KeyFactory keyFactory;
        private X509EncodedKeySpec keySpec;
        private byte[] key;
        private SecretKey keyAES;
        private byte[] cipherParamsEnc;
        private AlgorithmParameters cipherParams;
    }

    private static class Party2 {
        private byte[] pubKeyEnc;
        private PublicKey pubKey;
    }

    private byte[] sendAndReceive(byte[] data) throws IOException {
        refresh();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        try(PrintWriter out = new PrintWriter(new OutputStreamWriter(conn.getOutputStream(), UTF_8), true);
        ) {
            out.println(base64encode(data));
        }

        String input;
        try(BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream(), UTF_8));
        ) {
            input = in.readLine();
        }
        conn.disconnect();
        if(input == null) {
            throw new RuntimeException("Server did not provide required data for encryption handshake.");
        }
        return base64decode(input);
    }

    private void refresh() throws IOException {
        conn = (HttpURLConnection) host.openConnection();
    }

    public ChatCrypt(URL host) throws Exception {

        this.host = host;
        this.conn = (HttpURLConnection) host.openConnection();

        Self self = new Self();
        Party2 party2 = new Party2();

        DHParameterSpec dhParamSpec = new DHParameterSpec(otr1536Modulus, otr1536Base);
        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");

        self.keyPairGen = KeyPairGenerator.getInstance("DH");
        self.keyPairGen.initialize(dhParamSpec);
        self.keyPair = self.keyPairGen.generateKeyPair();
        self.keyAgree = KeyAgreement.getInstance("DH");
        self.keyAgree.init(self.keyPair.getPrivate());
        self.pubKeyEnc = self.keyPair.getPublic().getEncoded();

        // Client
        party2.pubKeyEnc = sendAndReceive(self.pubKeyEnc);

        self.keyFactory = KeyFactory.getInstance("DH");
        self.keySpec = new X509EncodedKeySpec(party2.pubKeyEnc);
        party2.pubKey = self.keyFactory.generatePublic(self.keySpec);

        self.keyAgree.doPhase(party2.pubKey, true);
        self.key = self.keyAgree.generateSecret();
        self.keyAES = new SecretKeySpec(Arrays.copyOf(sha256.digest(self.key), 16), "AES");

        cipherD = Cipher.getInstance("AES/CTR/PKCS5Padding");
        cipherE = Cipher.getInstance("AES/CTR/PKCS5Padding");

        // Client
        cipherE.init(Cipher.ENCRYPT_MODE, self.keyAES);
        self.cipherParamsEnc = cipherE.getParameters().getEncoded();
        sendAndReceive(self.cipherParamsEnc);

        self.cipherParams = AlgorithmParameters.getInstance("AES");
        self.cipherParams.init(self.cipherParamsEnc);

        cipherD.init(Cipher.DECRYPT_MODE, self.keyAES, self.cipherParams);
    }

}
