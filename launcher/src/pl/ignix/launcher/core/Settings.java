package pl.ignix.launcher.core;

import com.google.gson.Gson;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Launcher settings in %APPDATA%\.ignix\launcher.json. */
public final class Settings {

    private static final File FILE = new File(Dirs.BASE, "launcher.json");

    public int ramMb = defaultRam();
    public boolean hideLauncherWhilePlaying = true;

    public static Settings load() {
        try {
            if (FILE.isFile()) {
                Settings s = new Gson().fromJson(new String(Files.readAllBytes(FILE.toPath()), StandardCharsets.UTF_8), Settings.class);
                if (s != null) {
                    s.ramMb = Math.max(1024, Math.min(8192, s.ramMb));
                    return s;
                }
            }
        } catch (Exception ignored) {
        }
        return new Settings();
    }

    public void save() {
        try {
            Dirs.BASE.mkdirs();
            Files.write(FILE.toPath(), new Gson().toJson(this).getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    /** 2 GB on 8 GB machines, 3 GB on 16 GB+, 1.5 GB below. More doesn't help 1.8.9. */
    static int defaultRam() {
        try {
            com.sun.management.OperatingSystemMXBean os =
                    (com.sun.management.OperatingSystemMXBean) java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            long gb = os.getTotalPhysicalMemorySize() / (1024L * 1024 * 1024);
            return gb >= 15 ? 3072 : gb >= 7 ? 2048 : 1536;
        } catch (Throwable t) {
            return 2048;
        }
    }
}
