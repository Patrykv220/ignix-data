package pl.ignix.launcher.auth;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import pl.ignix.launcher.core.Http;
import pl.ignix.launcher.core.LauncherInfo;

import java.io.IOException;

/**
 * Microsoft sign-in for Minecraft: device code -> Microsoft token -> Xbox Live -> XSTS -> Minecraft token -> profile.
 * Public client (no secret). Tokens only go to Microsoft / Xbox / Minecraft servers.
 */
public final class MicrosoftAuth {

    private static final String AUTH = "https://login.microsoftonline.com/consumers/oauth2/v2.0/";
    private static final String SCOPE = "XboxLive.signin offline_access";
    private static final String FORM = "application/x-www-form-urlencoded";
    private static final String JSON = "application/json";

    /** Shown to the player: go to verificationUri and type userCode. */
    public static final class DeviceCode {
        public String deviceCode;
        public String userCode;
        public String verificationUri;
        public int interval;
        public long expiresAt;
    }

    /** Friendly error the UI can show as-is. */
    public static final class AuthException extends IOException {
        public AuthException(String message) {
            super(message);
        }
    }

    public DeviceCode startDeviceCode() throws IOException {
        Http.Response r = Http.post(AUTH + "devicecode", FORM,
                "client_id=" + LauncherInfo.MS_CLIENT_ID + "&scope=" + Http.urlEncode(SCOPE), JSON);
        JsonObject o = parse(r.body);
        if (r.code != 200) {
            throw new AuthException("Microsoft refused the sign-in request (" + err(o) + ").");
        }
        DeviceCode dc = new DeviceCode();
        dc.deviceCode = o.get("device_code").getAsString();
        dc.userCode = o.get("user_code").getAsString();
        dc.verificationUri = o.get("verification_uri").getAsString();
        dc.interval = Math.max(2, o.get("interval").getAsInt());
        dc.expiresAt = System.currentTimeMillis() + o.get("expires_in").getAsLong() * 1000L;
        return dc;
    }

    /** Waits until the player finishes signing in in the browser, then builds a full Minecraft account. */
    public Account waitForLogin(DeviceCode dc, java.util.function.BooleanSupplier cancelled) throws IOException, InterruptedException {
        int interval = dc.interval;
        while (System.currentTimeMillis() < dc.expiresAt) {
            if (cancelled.getAsBoolean()) {
                throw new AuthException("Sign-in cancelled.");
            }
            Thread.sleep(interval * 1000L);
            Http.Response r = Http.post(AUTH + "token", FORM,
                    "grant_type=urn:ietf:params:oauth:grant-type:device_code&client_id=" + LauncherInfo.MS_CLIENT_ID
                            + "&device_code=" + Http.urlEncode(dc.deviceCode), JSON);
            JsonObject o = parse(r.body);
            if (r.code == 200) {
                return finish(o.get("access_token").getAsString(), o.get("refresh_token").getAsString());
            }
            String e = err(o);
            if ("authorization_pending".equals(e)) {
                continue;
            }
            if ("slow_down".equals(e)) {
                interval += 5;
                continue;
            }
            if ("authorization_declined".equals(e)) {
                throw new AuthException("You declined the sign-in.");
            }
            if ("expired_token".equals(e)) {
                break;
            }
            throw new AuthException("Microsoft sign-in failed (" + e + ").");
        }
        throw new AuthException("The code expired – try again.");
    }

    /** Refreshes a premium account if its Minecraft token is expired or about to expire. */
    public Account refresh(Account a) throws IOException {
        if (!a.isPremium() || System.currentTimeMillis() < a.accessExpires - 10 * 60_000L) {
            return a;
        }
        Http.Response r = Http.post(AUTH + "token", FORM,
                "grant_type=refresh_token&client_id=" + LauncherInfo.MS_CLIENT_ID
                        + "&refresh_token=" + Http.urlEncode(a.refreshToken) + "&scope=" + Http.urlEncode(SCOPE), JSON);
        JsonObject o = parse(r.body);
        if (r.code != 200) {
            throw new AuthException("Your Microsoft login expired – sign in again.");
        }
        return finish(o.get("access_token").getAsString(), o.get("refresh_token").getAsString());
    }

    private Account finish(String msToken, String refreshToken) throws IOException {
        // 1. Xbox Live
        JsonObject xblReq = new JsonObject();
        JsonObject p = new JsonObject();
        p.addProperty("AuthMethod", "RPS");
        p.addProperty("SiteName", "user.auth.xboxlive.com");
        p.addProperty("RpsTicket", "d=" + msToken);
        xblReq.add("Properties", p);
        xblReq.addProperty("RelyingParty", "http://auth.xboxlive.com");
        xblReq.addProperty("TokenType", "JWT");
        Http.Response xbl = Http.post("https://user.auth.xboxlive.com/user/authenticate", JSON, xblReq.toString(), JSON);
        if (xbl.code != 200) {
            throw new AuthException("Xbox Live sign-in failed (HTTP " + xbl.code + ").");
        }
        JsonObject xblO = parse(xbl.body);
        String xblToken = xblO.get("Token").getAsString();
        String uhs = xblO.getAsJsonObject("DisplayClaims").getAsJsonArray("xui").get(0).getAsJsonObject().get("uhs").getAsString();

        // 2. XSTS
        JsonObject xstsReq = new JsonObject();
        JsonObject xp = new JsonObject();
        xp.addProperty("SandboxId", "RETAIL");
        JsonArray tokens = new JsonArray();
        tokens.add(new com.google.gson.JsonPrimitive(xblToken));
        xp.add("UserTokens", tokens);
        xstsReq.add("Properties", xp);
        xstsReq.addProperty("RelyingParty", "rp://api.minecraftservices.com/");
        xstsReq.addProperty("TokenType", "JWT");
        Http.Response xsts = Http.post("https://xsts.auth.xboxlive.com/xsts/authorize", JSON, xstsReq.toString(), JSON);
        if (xsts.code == 401) {
            String xerr = xsts.body.contains("2148916233") ? "This Microsoft account has no Xbox profile – sign in once at xbox.com first."
                    : xsts.body.contains("2148916238") ? "This is a child account – an adult has to add it to a Microsoft family."
                    : "Xbox refused this account.";
            throw new AuthException(xerr);
        }
        if (xsts.code != 200) {
            throw new AuthException("Xbox sign-in failed (HTTP " + xsts.code + ").");
        }
        String xstsToken = parse(xsts.body).get("Token").getAsString();

        // 3. Minecraft
        JsonObject mcReq = new JsonObject();
        mcReq.addProperty("identityToken", "XBL3.0 x=" + uhs + ";" + xstsToken);
        Http.Response mc = Http.post("https://api.minecraftservices.com/authentication/login_with_xbox", JSON, mcReq.toString(), JSON);
        if (mc.code == 403) {
            throw new AuthException("Microsoft sign-in works, but Mojang hasn't approved Ignix Launcher yet. "
                    + "Use an offline account until then.");
        }
        if (mc.code != 200) {
            throw new AuthException("Minecraft sign-in failed (HTTP " + mc.code + ").");
        }
        JsonObject mcO = parse(mc.body);
        String mcToken = mcO.get("access_token").getAsString();
        long expires = System.currentTimeMillis() + mcO.get("expires_in").getAsLong() * 1000L;

        // 4. Profile (also proves the account owns Minecraft)
        Http.Response prof = Http.get("https://api.minecraftservices.com/minecraft/profile", mcToken);
        if (prof.code == 404) {
            throw new AuthException("This Microsoft account doesn't own Minecraft: Java Edition.");
        }
        if (prof.code != 200) {
            throw new AuthException("Couldn't load your Minecraft profile (HTTP " + prof.code + ").");
        }
        JsonObject pr = parse(prof.body);
        Account a = new Account();
        a.type = Account.Type.MICROSOFT;
        a.name = pr.get("name").getAsString();
        a.uuid = pr.get("id").getAsString();
        a.accessToken = mcToken;
        a.accessExpires = expires;
        a.refreshToken = refreshToken;
        return a;
    }

    private static JsonObject parse(String s) {
        try {
            return new JsonParser().parse(s).getAsJsonObject();
        } catch (Exception e) {
            return new JsonObject();
        }
    }

    private static String err(JsonObject o) {
        return o.has("error") ? o.get("error").getAsString() : "unknown";
    }
}
