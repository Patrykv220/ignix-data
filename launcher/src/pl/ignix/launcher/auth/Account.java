package pl.ignix.launcher.auth;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** A player account: offline (nick only) or Microsoft (premium). */
public final class Account {

    public enum Type { OFFLINE, MICROSOFT }

    public Type type;
    public String name;
    public String uuid;          // without dashes
    public String accessToken;   // Minecraft token (premium) or "0"
    public long accessExpires;   // epoch millis
    public String refreshToken;  // Microsoft refresh token (premium only)

    public static Account offline(String nick) {
        Account a = new Account();
        a.type = Type.OFFLINE;
        a.name = nick;
        a.uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + nick).getBytes(StandardCharsets.UTF_8))
                .toString().replace("-", "");
        a.accessToken = "0";
        return a;
    }

    public boolean isPremium() {
        return type == Type.MICROSOFT;
    }

    public static boolean validNick(String nick) {
        return nick != null && nick.matches("[A-Za-z0-9_]{3,16}");
    }
}
