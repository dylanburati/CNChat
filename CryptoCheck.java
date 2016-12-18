import java.math.BigInteger;
import java.security.*;
import java.security.spec.*;
import java.util.Arrays;
import javax.crypto.*;
import javax.crypto.spec.*;
import javax.crypto.interfaces.*;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Alice is the client
 * Bob is the server
 *
 * Transmissions
 * Alice -> public key -> Bob
 * Bob -> public key -> Alice
 * Alice -> challenge (plaintext) -> Bob
 * Bob -> DES params, response (DES encrypted) -> Alice
 * Bob -> challenge -> Alice
 * Alice -> response -> Bob
 * Bob -> welcome -> Alice
 */

public class CryptoCheck {

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

    public static byte[] base64encode(byte[] b256) {
        int field;
        int tail = b256.length % 3;
        int length64 = (b256.length / 3 * 4) + new int[]{0, 2, 3}[tail];
        byte[] b64 = new byte[length64];
        int i256, i64;
        for (i256 = i64 = 0; i256 < b256.length - tail; i256 += 3) {
            field = ((b256[i256] & 255) << 16) |
                    ((b256[i256 + 1] & 255) << 8) |
                    (b256[i256 + 2] & 255);
            b64[i64++] = (byte) (((field >> 18) & 63) + 63);
            b64[i64++] = (byte) (((field >> 12) & 63) + 63);
            b64[i64++] = (byte) (((field >> 6) & 63) + 63);
            b64[i64++] = (byte) ((field & 63) + 63);
        }
        switch (tail) {
            case 1:
                field = b256[i256] & 255;
                b64[i64++] = (byte) (((field >> 2) & 63) + 63);
                b64[i64] = (byte) ((field & 3) + 63);
                break;
            case 2:
                field = ((b256[i256] & 255) << 8) |
                        (b256[i256 + 1] & 255);
                b64[i64++] = (byte) (((field >> 10) & 63) + 63);
                b64[i64++] = (byte) (((field >> 4) & 63) + 63);
                b64[i64] = (byte) ((field & 15) + 63);
        }
        return b64;
    }

    public static byte[] base64decode(byte[] b64) {
        int field;
        int tail64 = b64.length % 4;
        int tail256 = new int[]{0, 0, 1, 2}[tail64];
        int length256 = (b64.length - tail64) * 3 / 4 + tail256;
        byte[] b256 = new byte[length256];
        int i256, i64;
        for (i64 = i256 = 0; i64 < b64.length - tail64; i64 += 4) {
            field = ((b64[i64] - 63) << 18) |
                    ((b64[i64 + 1] - 63) << 12) |
                    ((b64[i64 + 2] - 63) << 6) |
                    (b64[i64 + 3] - 63);
            b256[i256++] = (byte) ((field >> 16) & 255);
            b256[i256++] = (byte) ((field >> 8) & 255);
            b256[i256++] = (byte) (field & 255);
        }
        switch (tail256) {
            case 1:
                field = ((b64[i64] - 63) << 2) |
                        (b64[i64 + 1] - 63);
                b256[i256] = (byte) field;
                break;
            case 2:
                field = ((b64[i64] - 63) << 10) |
                        ((b64[i64 + 1] - 63) << 4) |
                        (b64[i64 + 2] - 63);
                b256[i256++] = (byte) ((field >> 8) & 255);
                b256[i256] = (byte) (field & 255);
        }
        return b256;
    }

    private static void byte2hex(byte b, StringBuffer buf) {
        char[] hexChars = { '0', '1', '2', '3', '4', '5', '6', '7', '8',
                '9', 'A', 'B', 'C', 'D', 'E', 'F' };
        int high = ((b & 240) >> 4);
        int low = (b & 15);
        buf.append(hexChars[high]);
        buf.append(hexChars[low]);
    }

    private static String toHexString(byte[] block) {
        StringBuffer buf = new StringBuffer();

        int len = block.length;

        for (int i = 0; i < len; i++) {
            byte2hex(block[i], buf);
            if (i < len-1) {
                buf.append(":");
            }
        }
        return buf.toString();
    }

    public static void main(String argv[]) throws Exception {

        // Alice's parameters
        DHParameterSpec dhSkipParamSpec = new DHParameterSpec(skip1024Modulus, skip1024Base);

        // Alice's key pair
        KeyPairGenerator aliceKpairGen = KeyPairGenerator.getInstance("DH");
        aliceKpairGen.initialize(dhSkipParamSpec);
        KeyPair aliceKpair = aliceKpairGen.generateKeyPair();
        KeyAgreement aliceKeyAgree = KeyAgreement.getInstance("DH");
        aliceKeyAgree.init(aliceKpair.getPrivate());
        byte[] alicePubKeyEnc = aliceKpair.getPublic().getEncoded();

        // Bob processes Alice's public key
        KeyFactory bobKeyFac = KeyFactory.getInstance("DH");
        X509EncodedKeySpec x509KeySpec = new X509EncodedKeySpec(alicePubKeyEnc);
        PublicKey alicePubKey = bobKeyFac.generatePublic(x509KeySpec);

        // Bob's parameters
        DHParameterSpec dhParamSpec = ((DHPublicKey)alicePubKey).getParams();

        // Bob's key pair
        KeyPairGenerator bobKpairGen = KeyPairGenerator.getInstance("DH");
        bobKpairGen.initialize(dhParamSpec);
        KeyPair bobKpair = bobKpairGen.generateKeyPair();
        KeyAgreement bobKeyAgree = KeyAgreement.getInstance("DH");
        bobKeyAgree.init(bobKpair.getPrivate());
        byte[] bobPubKeyEnc = bobKpair.getPublic().getEncoded();

        // Alice processes Bob's public key
        KeyFactory aliceKeyFac = KeyFactory.getInstance("DH");
        x509KeySpec = new X509EncodedKeySpec(bobPubKeyEnc);
        PublicKey bobPubKey = aliceKeyFac.generatePublic(x509KeySpec);

        // Alice and Bob generate the shared secret
        aliceKeyAgree.doPhase(bobPubKey, true);
        bobKeyAgree.doPhase(alicePubKey, true);
        byte[] aliceKey = aliceKeyAgree.generateSecret();
        byte[] bobKey = bobKeyAgree.generateSecret();
        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        SecretKey aliceAES = new SecretKeySpec(Arrays.copyOf(sha256.digest(aliceKey), 16), "AES");
        SecretKey bobAES = new SecretKeySpec(Arrays.copyOf(sha256.digest(bobKey), 16), "AES");

        // Alice and Bob generate DES ciphers
        Cipher aliceCipher = Cipher.getInstance("AES/CTR/PKCS5Padding");
        Cipher bobCipher = Cipher.getInstance("AES/CTR/PKCS5Padding");
        bobCipher.init(Cipher.ENCRYPT_MODE, bobAES);

        // Prepare to use ciphers
        byte[] encodedParams = bobCipher.getParameters().getEncoded();
        AlgorithmParameters params = AlgorithmParameters.getInstance("AES");
        params.init(encodedParams);

        // Test
        aliceCipher.init(Cipher.DECRYPT_MODE, aliceAES, params);
        byte[] messageBobMem = ("Welcome to CyberNaysh Chat\n" +
                "This is how we chill from 93 'til \u221e").getBytes(UTF_8);
        byte[] messagePreWire = bobCipher.doFinal(messageBobMem);
        byte[] messageWire = base64encode(messagePreWire);
        byte[] messagePostWire = base64decode(messageWire);
        byte[] messageAliceMem = aliceCipher.doFinal(messagePostWire);
        System.out.println(new String(messageBobMem, UTF_8));
        System.out.println(toHexString(messagePreWire));
        System.out.println(toHexString(messageWire));
        System.out.println(toHexString(messagePostWire));
        System.out.println(new String(messageAliceMem, UTF_8));
        System.out.println();
        System.out.println(java.util.Arrays.equals(messagePreWire, messagePostWire) ? "Match" : "Failure");

    }

}