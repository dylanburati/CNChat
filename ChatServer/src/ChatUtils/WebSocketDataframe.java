package ChatUtils;

import static java.nio.charset.StandardCharsets.UTF_8;

public class WebSocketDataframe {

    public static String getText(byte[] b256) {
        if(b256.length < 5) {
            // minimum length bytes + mask bytes + minimum data bytes
            return null;
        }

        byte[] decoded = new byte[b256.length - 4];
        for(int j = 0; j < decoded.length; j++) {
            decoded[j] = (byte)((b256[j % 4] & 0xFF) ^ (b256[j + 4] & 0xFF));
        }
        return new String(decoded, UTF_8);
    }

    public static byte[] toFrame(int opcode, String msg) {
        if(opcode < 0 || opcode > 15) {
            throw new IllegalArgumentException("Opcode must be between 0 and 15");
        }
        byte[] data = msg.getBytes(UTF_8);
        int len = data.length;
        byte[] lenEnc;
        if(len > 0xFFFF) {
            lenEnc = new byte[9];
            lenEnc[0] = 127;
            for(int i = 0; i < 4; i++) {
                lenEnc[i + 1] = 0;
            }
            for(int i = 4; i < 8; i++) {
                lenEnc[i + 1] = (byte)((len >> (8 * (7 - i))) & 0xFF);
            }
        } else if(len > 125) {
            lenEnc = new byte[3];
            lenEnc[0] = 126;
            for(int i = 0; i < 2; i++) {
                lenEnc[i + 1] = (byte)((len >> (8 * (1 - i))) & 0xFF);
            }
        } else {
            lenEnc = new byte[] { (byte)len };
        }

        byte[] first = new byte[] { (byte)opcode };
        if(opcode != 0) first[0] |= 0x80;
        return byteArrayConcat(first,
                byteArrayConcat(lenEnc, data));

    }

    private static byte[] byteArrayConcat(byte[] a, byte[] b) {
        int aLen = a.length;
        int bLen = b.length;
        byte[] c = new byte[aLen + bLen];
        System.arraycopy(a, 0, c, 0, aLen);
        System.arraycopy(b, 0, c, aLen, bLen);
        return c;
    }
}
