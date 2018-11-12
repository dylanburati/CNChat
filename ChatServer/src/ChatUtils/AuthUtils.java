package ChatUtils;

public class AuthUtils {
    public static boolean isValidUUID(String s) {
        if(s == null || s.length() != 32) return false;
        for(int i = 0; i < 32; i++) {
            if(Character.digit(s.codePointAt(i), 16) == -1) {
                return false;
            }
        }
        return true;
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
