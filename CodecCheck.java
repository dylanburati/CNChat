import java.nio.charset.Charset;

public class CodecCheck {
    static String base16encode(String in) {
        byte[] b256 = in.getBytes();
        int[] b16 = new int[2 * b256.length];
        int idx = 0;
        for (int cp : b256) {
            b16[idx++] = (cp & (15 << 4)) >> 4;
            b16[idx++] = cp & 15;
        }
        StringBuilder out = new StringBuilder();
        for (int nibble : b16) {
            out.append((char) (64 + nibble));
        }
        return out.toString();
    }

    static String base16decode(String in) {
        byte[] b16 = in.getBytes();
        byte[] b256 = new byte[b16.length / 2];
        for (int i = 0; i < b16.length; i += 2) {
            b256[i / 2] = (byte) (((b16[i] - 64) << 4) + (b16[i + 1] - 64));
        }
        return new String(b256);
    }

    public static void main(String[] args) {
        System.out.println(Charset.availableCharsets());
        String shortTest = "Vn";
        String shortTestEnc = base16encode(shortTest);
        String shortTestDec = base16decode(shortTestEnc);
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
        String longTestEnc = base16encode(longTest);
        String longTestDec = base16decode(longTestEnc);
        System.out.println(shortTest);
        System.out.println(shortTestEnc);
        System.out.println(shortTestDec);
        System.out.println(longTest);
        System.out.println(longTestEnc);
        System.out.println(longTestDec);
    }
}
