package pl.ignix.launcher.core;

import com.google.gson.JsonObject;
import pl.ignix.launcher.auth.Account;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Builds the java command line for Ignix Client and starts it. */
public final class GameLauncher {

    private GameLauncher() {
    }

    public static List<String> buildCommand(JsonObject version, GameInstaller.Installed inst, File ignixJar,
                                            Account account, Settings settings) {
        List<String> cmd = new ArrayList<>();
        cmd.add(inst.javaExecutable.getAbsolutePath());
        cmd.add("-Xmx" + settings.ramMb + "M");
        cmd.add("-Xms" + Math.min(1024, settings.ramMb / 2) + "M");
        // We ship the runtime ourselves (Java 8), so these flags are always safe here.
        cmd.add("-XX:+UseG1GC");
        cmd.add("-XX:+UnlockExperimentalVMOptions");
        cmd.add("-XX:G1NewSizePercent=20");
        cmd.add("-XX:G1ReservePercent=20");
        cmd.add("-XX:MaxGCPauseMillis=50");
        cmd.add("-XX:G1HeapRegionSize=32M");
        cmd.add("-XX:+DisableAttachMechanism"); // blocks attach-based cheat injectors
        if (Dirs.isWindows()) {
            cmd.add("-XX:HeapDumpPath=MojangTricksIntelDriversForPerformance_javaw.exe_minecraft.exe.heapdump");
        }
        cmd.add("-Djava.library.path=" + inst.nativesDir.getAbsolutePath());
        cmd.add("-Dminecraft.launcher.brand=ignix-launcher");
        cmd.add("-Dminecraft.launcher.version=" + LauncherInfo.VERSION);
        cmd.add("-cp");
        StringBuilder cp = new StringBuilder(ignixJar.getAbsolutePath());
        for (File lib : inst.libraries) {
            cp.append(File.pathSeparatorChar).append(lib.getAbsolutePath());
        }
        cmd.add(cp.toString());
        cmd.add(version.get("mainClass").getAsString());

        String[] template = version.get("minecraftArguments").getAsString().split(" ");
        for (String arg : template) {
            cmd.add(arg
                    .replace("${auth_player_name}", account.name)
                    .replace("${version_name}", "Ignix")
                    .replace("${game_directory}", Dirs.game().getAbsolutePath())
                    .replace("${assets_root}", Dirs.assets().getAbsolutePath())
                    .replace("${assets_index_name}", inst.assetIndex)
                    .replace("${auth_uuid}", account.uuid)
                    .replace("${auth_access_token}", account.accessToken)
                    .replace("${user_properties}", "{}")
                    .replace("${user_type}", account.isPremium() ? "msa" : "legacy"));
        }
        return cmd;
    }

    /** Starts the game; its output goes to .ignix/logs/game-output.log. */
    public static Process start(List<String> cmd) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(Dirs.game());
        File log = new File(Dirs.logs(), "game-output.log");
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.to(log));
        return pb.start();
    }

    /** The command with the access token hidden – safe to show or log. */
    public static String redacted(List<String> cmd) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cmd.size(); i++) {
            String s = cmd.get(i);
            if (i > 0 && "--accessToken".equals(cmd.get(i - 1))) {
                s = "<hidden>";
            }
            sb.append(s).append(' ');
        }
        return sb.toString().trim();
    }
}
