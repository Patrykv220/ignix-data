package pl.ignix.launcher.auth;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import pl.ignix.launcher.core.Dirs;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Saves accounts to %APPDATA%\.ignix\accounts.dat.
 * On Windows the file is encrypted with DPAPI (CryptProtectData) – only your Windows user can read it,
 * so copying the file to another PC doesn't give anyone your login.
 */
public final class AccountStore {

    private static final File FILE = new File(Dirs.BASE, "accounts.dat");
    private static final Gson GSON = new GsonBuilder().create();

    private final List<Account> accounts = new ArrayList<>();
    private int selected;

    public void load() {
        accounts.clear();
        try {
            if (!FILE.isFile()) {
                return;
            }
            byte[] raw = Files.readAllBytes(FILE.toPath());
            String json = new String(unprotect(raw), StandardCharsets.UTF_8);
            Saved s = GSON.fromJson(json, Saved.class);
            if (s != null && s.accounts != null) {
                accounts.addAll(s.accounts);
                selected = Math.max(0, Math.min(accounts.size() - 1, s.selected));
            }
        } catch (Exception e) {
            // unreadable (other Windows user / corrupted) – start fresh
            accounts.clear();
        }
    }

    public synchronized void save() {
        try {
            Dirs.BASE.mkdirs();
            Saved s = new Saved();
            s.accounts = accounts;
            s.selected = selected;
            byte[] data = protect(GSON.toJson(s).getBytes(StandardCharsets.UTF_8));
            File tmp = new File(FILE.getPath() + ".tmp");
            Files.write(tmp.toPath(), data);
            FILE.delete();
            tmp.renameTo(FILE);
        } catch (Exception ignored) {
        }
    }

    public List<Account> all() {
        return accounts;
    }

    public Account selected() {
        return accounts.isEmpty() ? null : accounts.get(Math.min(selected, accounts.size() - 1));
    }

    public void select(Account a) {
        int i = accounts.indexOf(a);
        if (i >= 0) {
            selected = i;
            save();
        }
    }

    /** Adds or replaces (same name + type) and selects it. */
    public void put(Account a) {
        for (int i = 0; i < accounts.size(); i++) {
            Account o = accounts.get(i);
            if (o.type == a.type && o.uuid.equals(a.uuid)) {
                accounts.set(i, a);
                selected = i;
                save();
                return;
            }
        }
        accounts.add(a);
        selected = accounts.size() - 1;
        save();
    }

    public void remove(Account a) {
        accounts.remove(a);
        selected = 0;
        save();
    }

    private static byte[] protect(byte[] data) {
        if (Dirs.isWindows()) {
            try {
                return concat(new byte[]{'D', 'P', '1'}, com.sun.jna.platform.win32.Crypt32Util.cryptProtectData(data));
            } catch (Throwable ignored) {
            }
        }
        return concat(new byte[]{'P', 'L', '1'}, data);
    }

    private static byte[] unprotect(byte[] raw) {
        byte[] body = java.util.Arrays.copyOfRange(raw, 3, raw.length);
        if (raw[0] == 'D') {
            return com.sun.jna.platform.win32.Crypt32Util.cryptUnprotectData(body);
        }
        return body;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] r = new byte[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    private static final class Saved {
        List<Account> accounts = new ArrayList<>();
        int selected;
    }

    @SuppressWarnings("unused")
    private static final java.lang.reflect.Type LIST = new TypeToken<List<Account>>() { }.getType();
}
