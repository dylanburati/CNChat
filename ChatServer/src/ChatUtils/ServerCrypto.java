package ChatUtils;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.SecretKey;
import javax.crypto.spec.DHParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.math.BigInteger;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;

import static ChatUtils.Codecs.base64decode;
import static ChatUtils.Codecs.base64encode;

public class ServerCrypto {

    private static final BigInteger skip1024Modulus = new BigInteger(
            "F488FD584E49DBCD" +
            "20B49DE49107366B" +
            "336C380D451D0F7C" +
            "88B31C7C5B2D8EF6" +
            "F3C923C043F0A55B" +
            "188D8EBB558CB85D" +
            "38D334FD7C175743" +
            "A31D186CDE33212C" +
            "B52AFF3CE1B12940" +
            "18118D7C84A70A72" +
            "D686C40319C80729" +
            "7ACA950CD9969FAB" +
            "D00A509B0246D308" +
            "3D66A45D419F9C7C" +
            "BD894B221926BAAB" +
            "A25EC355E92F78C7", 16);
    private static final BigInteger skip1024Base = BigInteger.valueOf(2);
    private BufferedReader in;
    private PrintWriter out;

    public Cipher cipherD;
    public Cipher cipherE;

    private void send(byte[] data) {
        out.println(base64encode(data));
    }

    private byte[] receive() throws IOException {
        String data = in.readLine();
        return base64decode(data);
    }

    public ServerCrypto(BufferedReader in, PrintWriter out) throws Exception {

        this.in = in;
        this.out = out;

        class Self {
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
        class Client {
            private byte[] pubKeyEnc;
            private PublicKey pubKey;
        }
        Self self = new Self();
        Client user = new Client();

        DHParameterSpec dhSkipParamSpec = new DHParameterSpec(skip1024Modulus, skip1024Base);
        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");

        self.keyPairGen = KeyPairGenerator.getInstance("DH");
        self.keyPairGen.initialize(dhSkipParamSpec);
        self.keyPair = self.keyPairGen.generateKeyPair();
        self.keyAgree = KeyAgreement.getInstance("DH");
        self.keyAgree.init(self.keyPair.getPrivate());
        self.pubKeyEnc = self.keyPair.getPublic().getEncoded();

        user.pubKeyEnc = receive();
        send(self.pubKeyEnc);

        self.keyFactory = KeyFactory.getInstance("DH");
        self.keySpec = new X509EncodedKeySpec(user.pubKeyEnc);
        user.pubKey = self.keyFactory.generatePublic(self.keySpec);

        self.keyAgree.doPhase(user.pubKey, true);
        self.key = self.keyAgree.generateSecret();
        self.keyAES = new SecretKeySpec(Arrays.copyOf(sha256.digest(self.key), 16), "AES");

        cipherD = Cipher.getInstance("AES/CTR/PKCS5Padding");
        cipherE = Cipher.getInstance("AES/CTR/PKCS5Padding");

        cipherE.init(Cipher.ENCRYPT_MODE, self.keyAES);
        self.cipherParamsEnc = cipherE.getParameters().getEncoded();
        send(self.cipherParamsEnc);

        self.cipherParams = AlgorithmParameters.getInstance("AES");
        self.cipherParams.init(self.cipherParamsEnc);
        cipherD.init(Cipher.DECRYPT_MODE, self.keyAES, self.cipherParams);
    }

}
