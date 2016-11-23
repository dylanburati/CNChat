import java.util.Arrays;
import static java.nio.charset.StandardCharsets.UTF_8;

public class CodecCheck {
    public static String base64encode(String in) {
        byte[] b256 = in.getBytes(UTF_8);
        int field;
        int tail = b256.length % 3;
        int length64 = (b256.length / 3 * 4) + new int[]{0, 2, 3}[tail];
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
                b64[i64] = (byte) ((field & 3) + 63);
                break;
            case 2:
                field = (Byte.toUnsignedInt(b256[i256]) << 8) |
                        Byte.toUnsignedInt(b256[i256 + 1]);
                b64[i64++] = (byte) (((field & (63 << 10)) >> 10) + 63);
                b64[i64++] = (byte) (((field & (63 << 4)) >> 4) + 63);
                b64[i64] = (byte) ((field & 15) + 63);
        }
        return new String(b64, UTF_8);
    }

    public static String base64decode(String in) {
        byte[] b64 = in.getBytes(UTF_8);
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
            b256[i256++] = (byte) ((field & (255 << 16)) >> 16);
            b256[i256++] = (byte) ((field & (255 << 8)) >> 8);
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
                b256[i256++] = (byte) ((field & (255 << 8)) >> 8);
                b256[i256] = (byte) (field & 255);
        }
        return new String(b256, UTF_8);
    }

    public static void main(String[] args) {
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
        System.out.print("Nanos/run: ");
        long[] dts = new long[10];
        long t0 = System.nanoTime();
        for (int i = 0; i < 10; i++) {
            base64decode(base64encode(longTest));
            dts[i] = System.nanoTime() - t0;
            t0 += dts[i];
        }
        System.out.println(Arrays.toString(dts));
    }
}
