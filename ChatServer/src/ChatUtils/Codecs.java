package ChatUtils;

import static java.nio.charset.StandardCharsets.UTF_8;

public class Codecs {
    public static String base64encode(String in) {
        byte[] b256 = in.getBytes(UTF_8);
        int field;
        int tail = b256.length % 3;
        int length64 = (b256.length / 3 * 4) + new int[]{1, 3, 4}[tail];
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
        return new String(b64, UTF_8);
    }

    public static String base64decode(String in) {
        byte[] b64 = in.getBytes(UTF_8);
        int field;
        int tail256 = (int) b64[b64.length - 1] - 63;
        int tail64 = new int[]{1, 3, 4}[tail256];
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
}
