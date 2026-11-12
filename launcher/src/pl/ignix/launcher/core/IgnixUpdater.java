package pl.ignix.launcher.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Keeps Ignix Client up to date: reads the signed update.json, downloads the new jar and checks
 * its signature before it is ever started. Works offline with the last good jar.
 */
public final class IgnixUpdater {

    public static final class Info {
        public String version;
        public String changelog = "";
        public String jarUrl;
    }

    private final File jar = new File(Dirs.client(), "IgnixClient.jar");
    private final File versionFile = new File(Dirs.client(), "version.txt");
    private Info latest;

    /** Fetches update.json. Returns null when offline or the file isn't signed correctly. */
    public Info fetchLatest() {
        try {
            byte[] data = Http.getBytes(LauncherInfo.DATA_URL + "update.json");
            String sig = Http.getString(LauncherInfo.DATA_URL + "update.json.sig");
            if (!Signatures.verify(data, sig)) {
                return null;
            }
            JsonObject o = new JsonParser().parse(new String(data, StandardCharsets.UTF_8)).getAsJsonObject();
            Info info = new Info();
            info.version = o.get("version").getAsString();
            info.changelog = o.has("changelog") ? o.get("changelog").getAsString() : "";
            info.jarUrl = o.has("jar") ? o.get("jar").getAsString()
                    : "https://github.com/Patrykv220/ignix-data/releases/download/v" + info.version + "/IgnixClient.jar";
            latest = info;
            return info;
        } catch (Exception e) {
            return null;
        }
    }

    public String installedVersion() {
        try {
            return jar.isFile() && versionFile.isFile()
                    ? new String(Files.readAllBytes(versionFile.toPath()), StandardCharsets.UTF_8).trim() : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** Makes sure a verified IgnixClient.jar is present. Returns it. */
    public File ensureClient(Progress progress) throws IOException {
        Info info = latest != null ? latest : fetchLatest();
        String installed = installedVersion();
        if (info == null) {
            if (installed != null) {
                return jar; // offline – use what we have
            }
            throw new IOException("Can't reach the Ignix update server and Ignix isn't installed yet.");
        }
        if (info.version.equals(installed)) {
            return jar;
        }
        progress.update("Downloading Ignix " + info.version, -1);
        byte[] data = Http.getBytes(info.jarUrl);
        String sig = Http.getString(info.jarUrl + ".sig");
        if (!Signatures.verify(data, sig)) {
            if (installed != null) {
                return jar;
            }
            throw new IOException("Downloaded Ignix jar has an invalid signature – not starting it.");
        }
        File part = new File(jar.getPath() + ".part");
        Files.write(part.toPath(), data);
        if (jar.exists() && !jar.delete()) {
            throw new IOException("Close the game first – the Ignix jar is in use.");
        }
        if (!part.renameTo(jar)) {
            throw new IOException("Can't install the new Ignix jar.");
        }
        Files.write(versionFile.toPath(), info.version.getBytes(StandardCharsets.UTF_8));
        return jar;
    }

    public Info getLatest() {
        return latest;
    }
}
