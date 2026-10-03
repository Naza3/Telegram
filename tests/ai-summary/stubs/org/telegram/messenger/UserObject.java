package org.telegram.messenger;
import org.telegram.tgnet.TLRPC;
public final class UserObject {
    public static String getUserName(TLRPC.User user) {
        return user.first_name == null ? "" : user.first_name;
    }
}
