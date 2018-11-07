package ChatUtils;

import static java.nio.charset.StandardCharsets.UTF_8;

public class Codecs {
    public static String base64encode(byte[] b256) {
        int field;
        int tail = b256.length % 3;
        int length64 = (b256.length / 3 * 4) + new int[]{0, 2, 3}[tail];
        byte[] b64 = new byte[length64];
        int i256, i64;
        for(i256 = i64 = 0; i256 < b256.length - tail; i256 += 3) {
            field = ((b256[i256] & 255) << 16) |
                    ((b256[i256 + 1] & 255) << 8) |
                    (b256[i256 + 2] & 255);
            b64[i64++] = (byte) (((field >> 18) & 63) + 63);
            b64[i64++] = (byte) (((field >> 12) & 63) + 63);
            b64[i64++] = (byte) (((field >> 6) & 63) + 63);
            b64[i64++] = (byte) ((field & 63) + 63);
        }
        switch(tail) {
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
        return new String(b64, UTF_8);
    }

    public static byte[] base64decode(String in) {
        byte[] b64 = in.getBytes(UTF_8);
        int field;
        int tail64 = b64.length % 4;
        int tail256 = new int[]{0, 0, 1, 2}[tail64];
        int length256 = (b64.length - tail64) * 3 / 4 + tail256;
        byte[] b256 = new byte[length256];
        int i256, i64;
        for(i64 = i256 = 0; i64 < b64.length - tail64; i64 += 4) {
            field = ((b64[i64] - 63) << 18) |
                    ((b64[i64 + 1] - 63) << 12) |
                    ((b64[i64 + 2] - 63) << 6) |
                    (b64[i64 + 3] - 63);
            b256[i256++] = (byte) ((field >> 16) & 255);
            b256[i256++] = (byte) ((field >> 8) & 255);
            b256[i256++] = (byte) (field & 255);
        }
        switch(tail256) {
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

    public static byte[] crypt64decode(String in) {
        String cryptAlpha = "./ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        byte[] b64 = new byte[in.length()];
        for(int i = 0; i < b64.length; i++) {
            int c64 = cryptAlpha.indexOf(in.codePointAt(i));
            if(c64 == -1) {
                throw new RuntimeException("invalid character in crypt64 encoded string");
            }
            b64[i] = (byte)c64;
        }
        int field;
        int tail64 = b64.length % 4;
        int tail256 = new int[]{0, 0, 1, 2}[tail64];
        int length256 = (b64.length - tail64) * 3 / 4 + tail256;
        byte[] b256 = new byte[length256];
        int i256, i64;
        for(i64 = i256 = 0; i64 < b64.length - tail64; i64 += 4) {
            field = (b64[i64] << 18) |
                    (b64[i64 + 1] << 12) |
                    (b64[i64 + 2] << 6) |
                    b64[i64 + 3];
            b256[i256++] = (byte) ((field >> 16) & 255);
            b256[i256++] = (byte) ((field >> 8) & 255);
            b256[i256++] = (byte) (field & 255);
        }
        switch(tail256) {
            case 1:
                field = (b64[i64] << 2) |
                        b64[i64 + 1];
                b256[i256] = (byte) field;
                break;
            case 2:
                field = (b64[i64] << 10) |
                        (b64[i64 + 1] << 4) |
                        b64[i64 + 2];
                b256[i256++] = (byte) ((field >> 8) & 255);
                b256[i256] = (byte) (field & 255);
        }
        return b256;
    }
}
