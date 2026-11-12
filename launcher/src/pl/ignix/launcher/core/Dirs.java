package pl.ignix.launcher.core;

import java.io.File;

/** Everything Ignix Launcher writes lives in %APPDATA%\.ignix (separate from .minecraft). */
public final class Dirs {

    public static final File BASE;

    static {
        String appData = System.getenv("APPDATA");
        File root = appData != null && isWindows() ? new File(appData) : new File(System.getProperty("user.home"));
        BASE = new File(root, ".ignix");
    }

    private Dirs() {
    }

    public static File sub(String name) {
        File f = new File(BASE, name);
        if (!f.exists()) {
            f.mkdirs();
        }
        return f;
    }

    public static File libraries() { return sub("libraries"); }
    public static File assets() { return sub("assets"); }
    public static File natives() { return sub("natives/1.8.9"); }
    public static File runtime() { return sub("runtime"); }
    public static File client() { return sub("client"); }
    public static File logs() { return sub("logs"); }
    /** The Minecraft game directory (saves, options, resource packs, Ignix config). */
    public static File game() { return sub("game"); }

    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    public static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase().contains("mac");
    }

    /** Mojang's name for this OS in library rules: windows / osx / linux. */
    public static String mojangOs() {
        return isWindows() ? "windows" : isMac() ? "osx" : "linux";
    }

    public static boolean is64Bit() {
        String a = System.getProperty("os.arch", "");
        return a.contains("64");
    }
}
