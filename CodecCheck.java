import java.nio.charset.Charset;
import java.util.Arrays;


public class scratch_5 {
    private static final Charset cset = Charset.forName("UTF-8");

    private static String base64encode(String in) {
        byte[] b256 = in.getBytes(cset);
        int field;
        int tail = b256.length % 3;
        int length64 = (b256.length / 3 * 4) + new int[]{1,3,4}[tail];
        byte[] b64 = new byte[length64];
        int i256, i64;
        for (i256 = i64 = 0; i256 < b256.length - tail; i256 += 3) {
            field = (Byte.toUnsignedInt(b256[i256]) << 16) |
                    (Byte.toUnsignedInt(b256[i256 + 1]) << 8) |
                    Byte.toUnsignedInt(b256[i256 + 2]);
            b64[i64++] = (byte) (((field & (63 << 18)) >> 18) + 63);
            b64[i64++] = (byte) (((field & (63 << 12)) >> 12) + 63);
            b64[i64++] = (byte) (((field & (63 << 6)) >> 6) + 63);
            b64[i64++] = (byte) ((field & 63) + 63);
        }
        switch (tail) {
            case 1:
                field = Byte.toUnsignedInt(b256[i256]);
                b64[i64++] = (byte) (((field & (63 << 2)) >> 2) + 63);
                b64[i64++] = (byte) ((field & 3) + 63);
                break;
            case 2:
                field = (Byte.toUnsignedInt(b256[i256]) << 8) |
                        Byte.toUnsignedInt(b256[i256 + 1]);
                b64[i64++] = (byte) (((field & (63 << 10)) >> 10) + 63);
                b64[i64++] = (byte) (((field & (63 << 4)) >> 4) + 63);
                b64[i64++] = (byte) ((field & 15) + 63);
        }
        b64[i64] = (byte) (tail + 63);
        return new String(b64, cset);
    }

    private static String base64decode(String in) {
        byte[] b64 = in.getBytes(cset);
        int field;
        int tail256 = (int) b64[b64.length - 1] - 63;
        int tail64 = new int[]{1,3,4}[tail256];
        int length256 = (b64.length - tail64) * 3 / 4 + tail256;
        byte[] b256 = new byte[length256];
        int i256, i64;
        for (i64 = i256 = 0; i64 < b64.length - tail64; i64 += 4) {
            field = ((Byte.toUnsignedInt(b64[i64]) - 63) << 18) |
                    ((Byte.toUnsignedInt(b64[i64 + 1]) - 63) << 12) |
                    ((Byte.toUnsignedInt(b64[i64 + 2]) - 63) << 6) |
                    (Byte.toUnsignedInt(b64[i64 + 3]) - 63);
            b256[i256++] = (byte) ((field & (255 << 16)) >> 16);
            b256[i256++] = (byte) ((field & (255 << 8)) >> 8);
            b256[i256++] = (byte) (field & 255);
        }
        switch (tail256) {
            case 1:
                field = ((Byte.toUnsignedInt(b64[i64]) - 63) << 2) |
                        (Byte.toUnsignedInt(b64[i64 + 1]) - 63);
                b256[i256] = (byte) field;
                break;
            case 2:
                field = ((Byte.toUnsignedInt(b64[i64]) - 63) << 10) |
                        ((Byte.toUnsignedInt(b64[i64 + 1]) - 63) << 4) |
                        (Byte.toUnsignedInt(b64[i64 + 2]) - 63);
                b256[i256++] = (byte) ((field & (255 << 8)) >> 8);
                b256[i256] = (byte) (field & 255);
        }
        return new String(b256, cset);
    }

    private static String base16encode(String in) {
        byte[] b256 = in.getBytes();
        byte[] b16 = new byte[2 * b256.length];
        int idx = 0;
        for (int cp : b256) {
            b16[idx++] = (byte) (((cp & (15 << 4)) >> 4)+64);
            b16[idx++] = (byte) ((cp & 15)+64);
        }
        return new String(b16);
    }

    private static String base16decode(String in) {
        byte[] b16 = in.getBytes();
        byte[] b256 = new byte[b16.length / 2];
        for (int i = 0; i < b16.length; i += 2) {
            b256[i / 2] = (byte) (((b16[i] - 64) << 4) + (b16[i + 1] - 64));
        }
        return new String(b256);
    }

    public static void main(String[] args) {
        System.out.println(Charset.availableCharsets() + "\n");
        System.out.println(Charset.defaultCharset() + "\n");
        String shortTest = "Vn";
        String shortTestEnc = base64encode(shortTest);
        String shortTestDec = base64decode(shortTestEnc);
        String longTest = "22 (OVER S\u221e\u221eN)\n" +
                "10 d E A T h b R E a s T \u2684 \u2684\n" +
                "715 - CR\u2211\u2211KS\n" +
                "33 \u201cGOD\u201d\n" +
                "29 #Strafford APTS\n" +
                "666 \u0287\n" +
                "21 M\u25ca\u25caN WATER\n" +
                "8 (circle)\n" +
                "____45_____\n" +
                "00000 Million";
        String longTestEnc = base64encode(longTest);
        String longTestDec = base64decode(longTestEnc);
        System.out.println(shortTest);
        System.out.println(shortTestEnc);
        System.out.println(shortTestDec);
        System.out.println(longTest);
        System.out.println(longTestEnc);
        System.out.println(longTestDec);
        System.out.print("Base64: ");
        long[] dts = new long[10];
        long t0 = System.nanoTime();
        for (int i = 0; i < 10; i++) {
            base64decode(base64encode(longTest));
            dts[i] = System.nanoTime() - t0;
            t0 += dts[i];
        }
        System.out.println(Arrays.toString(dts));
        System.out.print("Base16: ");
        t0 = System.nanoTime();
        for (int i = 0; i < 10; i++) {
            base16decode(base16encode(longTest));
            dts[i] = System.nanoTime() - t0;
            t0 += dts[i];
        }
        System.out.println(Arrays.toString(dts));
    }
}
