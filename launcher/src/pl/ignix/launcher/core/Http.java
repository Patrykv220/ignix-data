package pl.ignix.launcher.core;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Small HTTP helper: GET text/bytes, POST json/form, and verified file downloads. */
public final class Http {

    public static final String USER_AGENT = "IgnixLauncher/" + LauncherInfo.VERSION;

    private Http() {
    }

    public static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", USER_AGENT);
        return c;
    }

    public static byte[] getBytes(String url) throws IOException {
        HttpURLConnection c = open(url);
        int code = c.getResponseCode();
        // GitHub release downloads redirect across hosts – follow manually if needed
        if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
            String loc = c.getHeaderField("Location");
            c.disconnect();
            return getBytes(loc);
        }
        if (code != 200) {
            throw new IOException("HTTP " + code + " for " + url);
        }
        try (InputStream in = c.getInputStream()) {
            return readAll(in);
        }
    }

    public static String getString(String url) throws IOException {
        return new String(getBytes(url), StandardCharsets.UTF_8);
    }

    /** POST and return [status, body]. */
    public static Response post(String url, String contentType, String body, String accept) throws IOException {
        HttpURLConnection c = open(url);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", contentType);
        if (accept != null) {
            c.setRequestProperty("Accept", accept);
        }
        try (OutputStream out = c.getOutputStream()) {
            out.write(body.getBytes(StandardCharsets.UTF_8));
        }
        return read(c);
    }

    public static Response get(String url, String bearer) throws IOException {
        HttpURLConnection c = open(url);
        if (bearer != null) {
            c.setRequestProperty("Authorization", "Bearer " + bearer);
        }
        return read(c);
    }

    private static Response read(HttpURLConnection c) throws IOException {
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        String text = in == null ? "" : new String(readAll(in), StandardCharsets.UTF_8);
        return new Response(code, text);
    }

    public static final class Response {
        public final int code;
        public final String body;

        Response(int code, String body) {
            this.code = code;
            this.body = body;
        }
    }

    /**
     * Downloads url to file unless it already exists with the right SHA-1 (or size, if no hash is known).
     * Writes to a .part file first, verifies, then renames – a broken download never replaces a good file.
     */
    public static boolean download(String url, File file, String sha1, long size) throws IOException {
        if (file.isFile() && (sha1 == null ? (size <= 0 || file.length() == size) : sha1.equalsIgnoreCase(sha1(file)))) {
            return false;
        }
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Can't create " + parent);
        }
        IOException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            File part = new File(file.getPath() + ".part");
            try {
                byte[] data = getBytes(url);
                try (FileOutputStream out = new FileOutputStream(part)) {
                    out.write(data);
                }
                if (sha1 != null && !sha1.equalsIgnoreCase(sha1(part))) {
                    throw new IOException("Checksum mismatch for " + url);
                }
                if (file.exists() && !file.delete()) {
                    throw new IOException("Can't replace " + file);
                }
                if (!part.renameTo(file)) {
                    throw new IOException("Can't move " + part);
                }
                return true;
            } catch (IOException e) {
                last = e;
                part.delete();
            }
        }
        throw last;
    }

    public static String sha1(File f) throws IOException {
        return hash(f, "SHA-1");
    }

    public static String sha256(File f) throws IOException {
        return hash(f, "SHA-256");
    }

    private static String hash(File f, String algo) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            MessageDigest md = MessageDigest.getInstance(algo);
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    public static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    public static String urlEncode(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return s;
        }
    }
}
