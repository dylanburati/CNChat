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

import static java.nio.charset.StandardCharsets.UTF_8;

public class EndToEndCryptoCheck {

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
    private static final int modBitLength = 1536;
    private static final int modByteLength = modBitLength / 8;

    private KeyPairGenerator keyPairGen;
    private KeyFactory keyFactory;

    private static class Party1 {
        private KeyPair identityKeyPair;
        private KeyAgreement identityKeyAgree;
        private byte[] identityKeyPubEnc;
        private PublicKey identityKeyPub;

        private KeyPair ephemeralKeyPair;
        private KeyAgreement ephemeralKeyAgree;
        private KeyAgreement ephemeralKeyAgree2;
        private byte[] ephemeralKeyPubEnc;
        private PublicKey ephemeralKeyPub;

        private byte[] secretMaterial = new byte[modByteLength * 3];
        private byte[] secretKey;
    }

    private static class Party2 {
        private KeyPair identityKeyPair;
        private KeyAgreement identityKeyAgree;
        private byte[] identityKeyPubEnc;
        private PublicKey identityKeyPub;

        private KeyPair prekeyPair;
        private KeyAgreement prekeyAgree;
        private KeyAgreement prekeyAgree2;
        private byte[] prekeyPubEnc;
        private PublicKey prekeyPub;

        private byte[] secretMaterial = new byte[modByteLength * 3];
        private byte[] secretKey;
    }

    public static void main(String[] args) throws Exception {
        Party1 alice = new Party1();
        Party2 bob = new Party2();
        KeyPairGenerator keyPairGen = KeyPairGenerator.getInstance("DH");
        KeyFactory keyFactory = KeyFactory.getInstance("DH");
        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");

        keyPairGen.initialize(new DHParameterSpec(otr1536Modulus, otr1536Base));

        // Bob generates identity key and prekey
        bob.identityKeyPair = keyPairGen.generateKeyPair();
        bob.identityKeyAgree = KeyAgreement.getInstance("DH");
        bob.identityKeyAgree.init(bob.identityKeyPair.getPrivate());
        bob.identityKeyPubEnc = bob.identityKeyPair.getPublic().getEncoded();
        bob.identityKeyPub = keyFactory.generatePublic(new X509EncodedKeySpec(bob.identityKeyPubEnc));

        bob.prekeyPair = keyPairGen.generateKeyPair();
        bob.prekeyAgree = KeyAgreement.getInstance("DH");
        bob.prekeyAgree.init(bob.prekeyPair.getPrivate());
        bob.prekeyAgree2 = KeyAgreement.getInstance("DH");
        bob.prekeyAgree2.init(bob.prekeyPair.getPrivate());
        bob.prekeyPubEnc = bob.prekeyPair.getPublic().getEncoded();
        bob.prekeyPub = keyFactory.generatePublic(new X509EncodedKeySpec(bob.prekeyPubEnc));


        // Alice generates identity and ephemeral keys
        alice.identityKeyPair = keyPairGen.generateKeyPair();
        alice.identityKeyAgree = KeyAgreement.getInstance("DH");
        alice.identityKeyAgree.init(alice.identityKeyPair.getPrivate());
        alice.identityKeyPubEnc = alice.identityKeyPair.getPublic().getEncoded();
        alice.identityKeyPub = keyFactory.generatePublic(new X509EncodedKeySpec(alice.identityKeyPubEnc));

        alice.ephemeralKeyPair = keyPairGen.generateKeyPair();
        alice.ephemeralKeyAgree = KeyAgreement.getInstance("DH");
        alice.ephemeralKeyAgree.init(alice.ephemeralKeyPair.getPrivate());
        alice.ephemeralKeyAgree2 = KeyAgreement.getInstance("DH");
        alice.ephemeralKeyAgree2.init(alice.ephemeralKeyPair.getPrivate());
        alice.ephemeralKeyPubEnc = alice.ephemeralKeyPair.getPublic().getEncoded();
        alice.ephemeralKeyPub = keyFactory.generatePublic(new X509EncodedKeySpec(alice.ephemeralKeyPubEnc));


        // Alice retrieves Bob's key bundle from the server
        alice.identityKeyAgree.doPhase(bob.prekeyPub, true);
        if(alice.identityKeyAgree.generateSecret(alice.secretMaterial, 0) != modByteLength)
            throw new RuntimeException("Key size is incorrect");
        alice.ephemeralKeyAgree.doPhase(bob.identityKeyPub, true);
        if(alice.ephemeralKeyAgree.generateSecret(alice.secretMaterial, modByteLength) != modByteLength)
            throw new RuntimeException("Key size is incorrect");
        alice.ephemeralKeyAgree2.doPhase(bob.prekeyPub, true);
        if(alice.ephemeralKeyAgree2.generateSecret(alice.secretMaterial, modByteLength * 2) != modByteLength)
            throw new RuntimeException("Key size is incorrect");
        alice.secretKey = Arrays.copyOf(sha256.digest(alice.secretMaterial), 16);

        System.out.println("Alice's secret\n" + Arrays.toString(alice.secretKey) + "\n");


        // Bob retrieves Alice's ephemeral key and initial message from the HttpServer
        bob.prekeyAgree.doPhase(alice.identityKeyPub, true);
        if(bob.prekeyAgree.generateSecret(bob.secretMaterial, 0) != modByteLength)
            throw new RuntimeException("Key size is incorrect");
        bob.identityKeyAgree.doPhase(alice.ephemeralKeyPub, true);
        if(bob.identityKeyAgree.generateSecret(bob.secretMaterial, modByteLength) != modByteLength)
            throw new RuntimeException("Key size is incorrect");
        bob.prekeyAgree2.doPhase(alice.ephemeralKeyPub, true);
        if(bob.prekeyAgree2.generateSecret(bob.secretMaterial, modByteLength * 2) != modByteLength)
            throw new RuntimeException("Key size is incorrect");
        bob.secretKey = Arrays.copyOf(sha256.digest(bob.secretMaterial), 16);

        System.out.println("Bob's secret\n" + Arrays.toString(bob.secretKey) + "\n");
    }

}
