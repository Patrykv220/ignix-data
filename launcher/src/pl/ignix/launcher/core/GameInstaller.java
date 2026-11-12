package pl.ignix.launcher.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Downloads everything Minecraft 1.8.9 needs from Mojang's official servers:
 * Java 8 (jre-legacy), libraries, natives and assets. Every file is checked against Mojang's SHA-1.
 * The version JSON (metadata only, no game code) ships inside the launcher.
 */
public final class GameInstaller {

    private static final String RUNTIME_INDEX =
            "https://launchermeta.mojang.com/v1/products/java-runtime/2ec0cc96c44e5a76b9c8b7c39df7210883d12871/all.json";
    private static final String RESOURCES = "https://resources.download.minecraft.net/";

    private final JsonObject version;

    public GameInstaller() {
        this(loadBundledVersion());
    }

    public GameInstaller(JsonObject version) {
        this.version = version;
    }

    public static JsonObject loadBundledVersion() {
        try (InputStream in = GameInstaller.class.getResourceAsStream("/ignix/1.8.9.json")) {
            return new JsonParser().parse(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (Exception e) {
            throw new IllegalStateException("Missing bundled 1.8.9.json", e);
        }
    }

    public JsonObject getVersion() {
        return version;
    }

    /** Result of an install: what the launcher needs to start the game. */
    public static final class Installed {
        public File javaExecutable;
        public final List<File> libraries = new ArrayList<>();
        public File nativesDir;
        public String assetIndex;
    }

    public Installed install(Progress progress) throws Exception {
        Installed result = new Installed();
        progress.update("Checking Java", -1);
        result.javaExecutable = installRuntime(progress);
        installLibraries(result, progress);
        result.assetIndex = installAssets(progress);
        return result;
    }

    // ------------------------------------------------------------------ Java runtime

    private static String runtimePlatform() {
        if (Dirs.isWindows()) {
            return Dirs.is64Bit() ? "windows-x64" : "windows-x86";
        }
        if (Dirs.isMac()) {
            return "mac-os";
        }
        return Dirs.is64Bit() ? "linux" : "linux-i386";
    }

    /** Mojang's own Java 8 build for Minecraft (jre-legacy). Falls back to the Java running the launcher. */
    File installRuntime(Progress progress) {
        String component = version.has("javaVersion")
                ? version.getAsJsonObject("javaVersion").get("component").getAsString() : "jre-legacy";
        File home = new File(Dirs.runtime(), component);
        File exe = new File(home, Dirs.isWindows() ? "bin/javaw.exe" : "bin/java");
        File marker = new File(home, ".installed");
        if (exe.isFile() && marker.isFile()) {
            return exe;
        }
        try {
            JsonObject all = parse(Http.getString(RUNTIME_INDEX));
            JsonObject platform = all.getAsJsonObject(runtimePlatform());
            JsonArray builds = platform == null ? null : platform.getAsJsonArray(component);
            if (builds == null || builds.size() == 0) {
                return systemJava();
            }
            String manifestUrl = builds.get(0).getAsJsonObject().getAsJsonObject("manifest").get("url").getAsString();
            JsonObject files = parse(Http.getString(manifestUrl)).getAsJsonObject("files");
            List<Job> jobs = new ArrayList<>();
            for (Map.Entry<String, JsonElement> e : files.entrySet()) {
                JsonObject f = e.getValue().getAsJsonObject();
                File target = new File(home, e.getKey());
                String type = f.get("type").getAsString();
                if ("directory".equals(type)) {
                    target.mkdirs();
                } else if ("file".equals(type)) {
                    JsonObject raw = f.getAsJsonObject("downloads").getAsJsonObject("raw");
                    boolean executable = f.has("executable") && f.get("executable").getAsBoolean();
                    jobs.add(new Job(raw.get("url").getAsString(), target, raw.get("sha1").getAsString(),
                            raw.get("size").getAsLong(), executable));
                }
                // "link" entries only exist on mac/linux and aren't needed to run java
            }
            runJobs("Downloading Java", jobs, progress);
            Files.write(marker.toPath(), "ok".getBytes(StandardCharsets.UTF_8));
            return exe;
        } catch (Exception e) {
            return systemJava();
        }
    }

    private static File systemJava() {
        File home = new File(System.getProperty("java.home"));
        return new File(home, Dirs.isWindows() ? "bin/javaw.exe" : "bin/java");
    }

    // ------------------------------------------------------------------ libraries + natives

    void installLibraries(Installed result, Progress progress) throws Exception {
        List<Job> jobs = new ArrayList<>();
        List<File[]> nativeJars = new ArrayList<>(); // [jar, excludes...]
        List<List<String>> nativeExcludes = new ArrayList<>();
        for (JsonElement el : version.getAsJsonArray("libraries")) {
            JsonObject lib = el.getAsJsonObject();
            if (!rulesAllow(lib)) {
                continue;
            }
            JsonObject downloads = lib.has("downloads") ? lib.getAsJsonObject("downloads") : null;
            if (downloads == null) {
                continue;
            }
            if (lib.has("natives")) {
                JsonObject natives = lib.getAsJsonObject("natives");
                if (!natives.has(Dirs.mojangOs())) {
                    continue;
                }
                String classifier = natives.get(Dirs.mojangOs()).getAsString().replace("${arch}", Dirs.is64Bit() ? "64" : "32");
                JsonObject cls = downloads.has("classifiers") ? downloads.getAsJsonObject("classifiers") : null;
                if (cls == null || !cls.has(classifier)) {
                    continue;
                }
                JsonObject art = cls.getAsJsonObject(classifier);
                File jar = new File(Dirs.libraries(), art.get("path").getAsString());
                jobs.add(job(art, jar));
                nativeJars.add(new File[]{jar});
                List<String> ex = new ArrayList<>();
                if (lib.has("extract") && lib.getAsJsonObject("extract").has("exclude")) {
                    for (JsonElement x : lib.getAsJsonObject("extract").getAsJsonArray("exclude")) {
                        ex.add(x.getAsString());
                    }
                }
                nativeExcludes.add(ex);
            } else if (downloads.has("artifact")) {
                JsonObject art = downloads.getAsJsonObject("artifact");
                File jar = new File(Dirs.libraries(), art.get("path").getAsString());
                jobs.add(job(art, jar));
                result.libraries.add(jar);
            }
        }
        runJobs("Downloading libraries", jobs, progress);

        progress.update("Unpacking natives", -1);
        File nativesDir = Dirs.natives();
        for (int i = 0; i < nativeJars.size(); i++) {
            extract(nativeJars.get(i)[0], nativesDir, nativeExcludes.get(i));
        }
        result.nativesDir = nativesDir;
    }

    /** Mojang library rules: allow/disallow by OS. No rules = allowed. */
    static boolean rulesAllow(JsonObject lib) {
        if (!lib.has("rules")) {
            return true;
        }
        boolean allowed = false;
        for (JsonElement el : lib.getAsJsonArray("rules")) {
            JsonObject rule = el.getAsJsonObject();
            boolean matches = true;
            if (rule.has("os")) {
                JsonObject os = rule.getAsJsonObject("os");
                if (os.has("name") && !os.get("name").getAsString().equals(Dirs.mojangOs())) {
                    matches = false;
                }
            }
            if (matches) {
                allowed = "allow".equals(rule.get("action").getAsString());
            }
        }
        return allowed;
    }

    private static void extract(File jar, File dir, List<String> excludes) throws IOException {
        try (ZipFile zip = new ZipFile(jar)) {
            java.util.Enumeration<? extends ZipEntry> en = zip.entries();
            outer:
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory()) {
                    continue;
                }
                for (String ex : excludes) {
                    if (e.getName().startsWith(ex)) {
                        continue outer;
                    }
                }
                File out = new File(dir, e.getName());
                if (!out.getCanonicalPath().startsWith(dir.getCanonicalPath())) {
                    continue; // zip-slip guard
                }
                if (out.isFile() && out.length() == e.getSize()) {
                    continue;
                }
                out.getParentFile().mkdirs();
                try (InputStream in = zip.getInputStream(e); FileOutputStream fo = new FileOutputStream(out)) {
                    fo.write(Http.readAll(in));
                }
            }
        }
    }

    // ------------------------------------------------------------------ assets

    String installAssets(Progress progress) throws Exception {
        JsonObject idx = version.getAsJsonObject("assetIndex");
        String id = idx.get("id").getAsString();
        File indexFile = new File(Dirs.assets(), "indexes/" + id + ".json");
        Http.download(idx.get("url").getAsString(), indexFile, idx.get("sha1").getAsString(), idx.get("size").getAsLong());
        JsonObject objects;
        try (InputStreamReader r = new InputStreamReader(new java.io.FileInputStream(indexFile), StandardCharsets.UTF_8)) {
            objects = new JsonParser().parse(r).getAsJsonObject().getAsJsonObject("objects");
        }
        List<Job> jobs = new ArrayList<>();
        for (Map.Entry<String, JsonElement> e : objects.entrySet()) {
            JsonObject o = e.getValue().getAsJsonObject();
            String hash = o.get("hash").getAsString();
            String sub = hash.substring(0, 2) + "/" + hash;
            // assets: size check is enough on later runs, hash is checked on first download
            jobs.add(new Job(RESOURCES + sub, new File(Dirs.assets(), "objects/" + sub), hash, o.get("size").getAsLong(), false));
        }
        runJobs("Downloading game assets", jobs, progress);
        return id;
    }

    // ------------------------------------------------------------------ download jobs

    private static Job job(JsonObject artifact, File target) {
        return new Job(artifact.get("url").getAsString(), target, artifact.get("sha1").getAsString(),
                artifact.get("size").getAsLong(), false);
    }

    static final class Job {
        final String url;
        final File target;
        final String sha1;
        final long size;
        final boolean executable;

        Job(String url, File target, String sha1, long size, boolean executable) {
            this.url = url;
            this.target = target;
            this.sha1 = sha1;
            this.size = size;
            this.executable = executable;
        }

        boolean upToDate() {
            return target.isFile() && target.length() == size;
        }
    }

    private static void runJobs(String label, List<Job> jobs, Progress progress) throws Exception {
        List<Job> todo = new ArrayList<>();
        for (Job j : jobs) {
            if (!j.upToDate()) {
                todo.add(j);
            }
        }
        if (todo.isEmpty()) {
            return;
        }
        ExecutorService pool = Executors.newFixedThreadPool(8);
        AtomicInteger done = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (Job j : todo) {
            futures.add(pool.submit(() -> {
                Http.download(j.url, j.target, j.sha1, j.size);
                if (j.executable) {
                    j.target.setExecutable(true);
                }
                int d = done.incrementAndGet();
                progress.update(label + " (" + d + "/" + todo.size() + ")", d / (double) todo.size());
                return null;
            }));
        }
        pool.shutdown();
        try {
            for (Future<?> f : futures) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private static JsonObject parse(String s) {
        return new JsonParser().parse(s).getAsJsonObject();
    }
}
