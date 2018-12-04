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
}
