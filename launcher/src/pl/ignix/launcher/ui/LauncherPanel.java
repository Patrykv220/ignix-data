package pl.ignix.launcher.ui;

import pl.ignix.launcher.auth.Account;
import pl.ignix.launcher.auth.AccountStore;
import pl.ignix.launcher.auth.MicrosoftAuth;
import pl.ignix.launcher.core.Dirs;
import pl.ignix.launcher.core.GameInstaller;
import pl.ignix.launcher.core.GameLauncher;
import pl.ignix.launcher.core.IgnixUpdater;
import pl.ignix.launcher.core.LauncherInfo;
import pl.ignix.launcher.core.Settings;

import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The whole launcher UI, painted by hand: dark slate, bevelled "inventory" panels, pixel headings.
 * Real Swing components are used only for the offline-nick text field.
 */
public final class LauncherPanel extends JPanel {

    static final int W = 900;
    static final int H = 540;

    // palette – warm darks + one ember accent, nothing else
    private static final Color BG = new Color(0x141418);
    private static final Color PANEL = new Color(0x1E1E24);
    private static final Color LIGHT = new Color(0x34343E);
    private static final Color DARK = new Color(0x08080A);
    private static final Color TEXT = new Color(0xE8E6E3);
    private static final Color DIM = new Color(0x8C8A86);
    private static final Color MUTED = new Color(0x55534F);
    private static final Color EMBER = new Color(0xFF6A00);
    private static final Color EMBER_DARK = new Color(0xB8430A);
    private static final Color EMBER_LIGHT = new Color(0xFFB02E);
    private static final Color RED = new Color(0xE5534B);

    private enum Overlay { NONE, ACCOUNTS, DEVICE_CODE }

    private final JFrame frame;
    private final AccountStore accounts = new AccountStore();
    private final Settings settings = Settings.load();
    private final IgnixUpdater updater = new IgnixUpdater();
    private final GameInstaller installer = new GameInstaller();
    private final JTextField nickField = new JTextField();

    private final List<Hit> hits = new ArrayList<>();
    private BufferedImage background;
    private BufferedImage flame;
    private Font body;
    private Font bodyBold;
    private Font small;

    private Overlay overlay = Overlay.NONE;
    private volatile boolean busy;
    private volatile String status = "";
    private volatile boolean statusError;
    private volatile double progress = -1;
    private volatile String changelog = "Checking for news...";
    private volatile String latestVersion;
    private volatile MicrosoftAuth.DeviceCode deviceCode;
    private volatile boolean cancelLogin;
    private int mouseX = -1;
    private int mouseY = -1;
    private int dragOffX;
    private int dragOffY;
    private boolean draggingSlider;

    public LauncherPanel(JFrame frame) {
        this(frame, true);
    }

    private LauncherPanel(JFrame frame, boolean live) {
        this.frame = frame;
        setLayout(null);
        setDoubleBuffered(true);
        if (live) {
            accounts.load();
        }
        loadAssets();

        nickField.setFont(body);
        nickField.setBackground(DARK);
        nickField.setForeground(TEXT);
        nickField.setCaretColor(EMBER);
        nickField.setBorder(javax.swing.BorderFactory.createEmptyBorder(4, 8, 4, 8));
        nickField.setVisible(false);
        nickField.addActionListener(e -> addOffline());
        add(nickField);

        MouseAdapter ma = new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                mouseX = e.getX();
                mouseY = e.getY();
                setCursor(hitAt(mouseX, mouseY) != null ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
                repaint();
            }

            @Override
            public void mouseExited(MouseEvent e) {
                mouseX = -1;
                mouseY = -1;
                repaint();
            }

            @Override
            public void mousePressed(MouseEvent e) {
                Hit h = hitAt(e.getX(), e.getY());
                if (h != null) {
                    if (h.slider) {
                        draggingSlider = true;
                        setRamFromMouse(e.getX(), h.rect);
                    } else if (h.action != null) {
                        h.action.run();
                    }
                    repaint();
                } else if (e.getY() < 44) {
                    dragOffX = e.getX();
                    dragOffY = e.getY();
                }
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (draggingSlider) {
                    for (Hit h : hits) {
                        if (h.slider) {
                            setRamFromMouse(e.getX(), h.rect);
                        }
                    }
                    repaint();
                } else if (dragOffY > 0 && dragOffY < 44) {
                    java.awt.Point p = e.getLocationOnScreen();
                    frame.setLocation(p.x - dragOffX, p.y - dragOffY);
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (draggingSlider) {
                    settings.save();
                }
                draggingSlider = false;
                dragOffY = 0;
            }
        };
        addMouseListener(ma);
        addMouseMotionListener(ma);

        if (!live) {
            return;
        }
        if (accounts.all().isEmpty()) {
            overlay = Overlay.ACCOUNTS;
        }
        new Thread(this::loadNews, "news").start();
    }

    // ------------------------------------------------------------------ assets

    private void loadAssets() {
        String family = pickFont("Segoe UI", "Inter", "Helvetica Neue", "Dialog");
        body = new Font(family, Font.PLAIN, 13);
        bodyBold = new Font(family, Font.BOLD, 13);
        small = new Font(family, Font.PLAIN, 11);
        try (InputStream in = getClass().getResourceAsStream("/ignix/flame16.png")) {
            flame = in == null ? null : ImageIO.read(in);
        } catch (Exception ignored) {
        }
        background = makeBackground();
    }

    private static String pickFont(String... names) {
        java.util.Set<String> av = new java.util.HashSet<>(java.util.Arrays.asList(
                java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()));
        for (String n : names) {
            if (av.contains(n)) {
                return n;
            }
        }
        return "Dialog";
    }

    /** Dark slate made of 4px "blocks" with tiny random shade changes + a huge faded pixel flame. */
    private BufferedImage makeBackground() {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        Random r = new Random(1337);
        for (int y = 0; y < H; y += 4) {
            for (int x = 0; x < W; x += 4) {
                int d = r.nextInt(7) - 3;
                g.setColor(new Color(clamp(0x14 + d), clamp(0x14 + d), clamp(0x18 + d)));
                g.fillRect(x, y, 4, 4);
            }
        }
        if (flame != null) {
            int scale = 26;
            int ox = W - 16 * scale + 60;
            int oy = H - 16 * scale + 40;
            for (int py = 0; py < 16; py++) {
                for (int px = 0; px < 16; px++) {
                    int argb = flame.getRGB(px, py);
                    if ((argb >>> 24) == 0) {
                        continue;
                    }
                    Color c = new Color(argb);
                    g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 14));
                    g.fillRect(ox + px * scale, oy + py * scale, scale, scale);
                }
            }
        }
        g.dispose();
        return img;
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }

    // ------------------------------------------------------------------ painting

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics;
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        hits.clear();
        g.drawImage(background, 0, 0, null);

        drawTitleBar(g);
        drawNews(g);
        drawMainPanel(g);
        if (overlay == Overlay.ACCOUNTS) {
            drawAccounts(g);
        } else if (overlay == Overlay.DEVICE_CODE) {
            drawDeviceCode(g);
        }
        nickField.setVisible(overlay == Overlay.ACCOUNTS);
    }

    private void drawTitleBar(Graphics2D g) {
        if (flame != null) {
            g.drawImage(flame, 20, 12, 32, 32, null);
        }
        PixelFont.draw(g, "IGNIX", 62, 18, 3, TEXT, true);
        g.setFont(small);
        g.setColor(MUTED);
        g.drawString("launcher " + LauncherInfo.VERSION, 62 + PixelFont.width("IGNIX", 3) + 10, 38);

        // window buttons: plain pixel glyphs, no circles
        Rectangle min = new Rectangle(W - 74, 12, 26, 24);
        Rectangle close = new Rectangle(W - 44, 12, 26, 24);
        boolean hm = min.contains(mouseX, mouseY);
        boolean hc = close.contains(mouseX, mouseY);
        if (hm) {
            fill(g, min, LIGHT);
        }
        if (hc) {
            fill(g, close, RED);
        }
        g.setColor(TEXT);
        g.fillRect(min.x + 8, min.y + 15, 10, 2);
        for (int i = 0; i < 10; i += 2) {
            g.fillRect(close.x + 8 + i, close.y + 7 + i, 2, 2);
            g.fillRect(close.x + 16 - i, close.y + 7 + i, 2, 2);
        }
        hits.add(new Hit(min, () -> frame.setState(java.awt.Frame.ICONIFIED)));
        hits.add(new Hit(close, () -> System.exit(0)));
    }

    private void drawNews(Graphics2D g) {
        int x = 24;
        int y = 82;
        PixelFont.draw(g, "WHAT'S NEW", x, y, 2, EMBER, true);
        y += 26;
        g.setFont(body);
        g.setColor(TEXT);
        String head = latestVersion != null ? "Ignix " + latestVersion : "Ignix";
        g.setFont(bodyBold);
        g.drawString(head, x, y + 12);
        y += 22;
        g.setFont(body);
        g.setColor(DIM);
        for (String line : wrap(g.getFontMetrics(), changelog, 260)) {
            g.drawString(line, x, y + 12);
            y += 18;
            if (y > 400) {
                break;
            }
        }

        // quick links at the bottom-left
        int ly = H - 70;
        g.setFont(small);
        link(g, "Open game folder", x, ly, () -> open(Dirs.game().toURI().toString()));
        link(g, "Ignix on GitHub", x, ly + 18, () -> open("https://github.com/Patrykv220/ignix-data"));
        g.setColor(MUTED);
        g.drawString("Not affiliated with Mojang or Microsoft.", x, H - 18);
    }

    private void drawMainPanel(Graphics2D g) {
        int px = 330;
        int py = 74;
        int pw = W - px - 26;
        int ph = H - py - 28;
        bevel(g, px, py, pw, ph, PANEL, LIGHT, DARK);

        int x = px + 24;
        int y = py + 24;

        // account slot
        PixelFont.draw(g, "ACCOUNT", x, y, 2, DIM, false);
        y += 24;
        Account acc = accounts.selected();
        Rectangle slot = new Rectangle(x, y, pw - 48, 58);
        boolean hs = slot.contains(mouseX, mouseY);
        bevel(g, slot.x, slot.y, slot.width, slot.height, hs ? new Color(0x26262E) : new Color(0x18181D), DARK, LIGHT);
        drawAvatar(g, acc, slot.x + 10, slot.y + 9);
        g.setFont(bodyBold);
        g.setColor(TEXT);
        g.drawString(acc == null ? "No account yet" : acc.name, slot.x + 62, slot.y + 26);
        g.setFont(small);
        g.setColor(acc != null && acc.isPremium() ? EMBER_LIGHT : DIM);
        g.drawString(acc == null ? "Click to add one" : acc.isPremium() ? "Microsoft account" : "Offline account (non-premium servers)",
                slot.x + 62, slot.y + 44);
        g.setColor(DIM);
        g.drawString("change", slot.x + slot.width - 54, slot.y + 34);
        hits.add(new Hit(slot, () -> overlay = Overlay.ACCOUNTS));
        y += 78;

        // memory
        PixelFont.draw(g, "MEMORY", x, y, 2, DIM, false);
        g.setFont(bodyBold);
        g.setColor(TEXT);
        String ram = String.format(java.util.Locale.ROOT, "%.1f GB", settings.ramMb / 1024.0);
        g.drawString(ram, x + pw - 48 - g.getFontMetrics().stringWidth(ram), y + 13);
        y += 26;
        Rectangle track = new Rectangle(x, y, pw - 48, 14);
        bevel(g, track.x, track.y, track.width, track.height, new Color(0x101013), DARK, LIGHT);
        double f = (settings.ramMb - 1024) / (8192.0 - 1024);
        int fillW = (int) ((track.width - 4) * f);
        g.setColor(EMBER_DARK);
        g.fillRect(track.x + 2, track.y + 2, fillW, track.height - 4);
        g.setColor(EMBER);
        g.fillRect(track.x + 2, track.y + 2, fillW, 3);
        int kx = track.x + 2 + fillW - 5;
        bevel(g, kx, track.y - 4, 10, track.height + 8, new Color(0xC9C6C0), Color.WHITE, new Color(0x6D6A66));
        hits.add(new Hit(new Rectangle(track.x - 6, track.y - 8, track.width + 12, track.height + 16), true));
        g.setFont(small);
        g.setColor(MUTED);
        g.drawString("2 GB is plenty for 1.8.9. More doesn't mean more FPS.", x, y + 34);
        y += 62;

        // option: hide launcher while playing
        Rectangle box = new Rectangle(x, y, 16, 16);
        Rectangle opt = new Rectangle(x, y - 2, 260, 20);
        bevel(g, box.x, box.y, box.width, box.height, new Color(0x101013), DARK, LIGHT);
        if (settings.hideLauncherWhilePlaying) {
            g.setColor(EMBER);
            g.fillRect(box.x + 4, box.y + 4, 8, 8);
        }
        g.setFont(body);
        g.setColor(opt.contains(mouseX, mouseY) ? TEXT : DIM);
        g.drawString("Hide the launcher while playing", x + 26, y + 13);
        hits.add(new Hit(opt, () -> {
            settings.hideLauncherWhilePlaying = !settings.hideLauncherWhilePlaying;
            settings.save();
        }));

        // PLAY
        Rectangle play = new Rectangle(x, py + ph - 110, pw - 48, 62);
        boolean hp = !busy && play.contains(mouseX, mouseY);
        Color base = busy ? new Color(0x3A2A20) : hp ? new Color(0xFF7A14) : EMBER;
        bevel(g, play.x, play.y, play.width, play.height, base,
                busy ? new Color(0x4A3628) : EMBER_LIGHT, busy ? DARK : new Color(0x6E2604));
        if (busy && progress >= 0) {
            g.setColor(new Color(0xFF6A00));
            g.fillRect(play.x + 3, play.y + play.height - 9, (int) ((play.width - 6) * progress), 6);
        }
        String label = busy ? "WORKING" : "PLAY";
        int lw = PixelFont.width(label, 4);
        PixelFont.draw(g, label, play.x + (play.width - lw) / 2, play.y + 17 + (hp ? -1 : 0), 4,
                busy ? DIM : Color.WHITE, true);
        if (!busy) {
            hits.add(new Hit(play, this::play));
        }

        // status
        g.setFont(small);
        g.setColor(statusError ? RED : DIM);
        String st = status.isEmpty()
                ? "Minecraft 1.8.9 + Ignix" + (updater.installedVersion() != null ? " " + updater.installedVersion() : "")
                : status;
        g.drawString(st, x, py + ph - 26);
    }

    private void drawAvatar(Graphics2D g, Account acc, int x, int y) {
        // our own 8x8 pixel avatar: a block in a colour from the nick, with the first letter on it
        int seed = acc == null ? 0 : acc.name.hashCode();
        Random r = new Random(seed);
        Color c = Color.getHSBColor(r.nextFloat(), 0.45f, 0.55f);
        for (int py = 0; py < 8; py++) {
            for (int px = 0; px < 8; px++) {
                float k = 0.85f + r.nextFloat() * 0.3f;
                g.setColor(new Color(clamp((int) (c.getRed() * k)), clamp((int) (c.getGreen() * k)), clamp((int) (c.getBlue() * k))));
                g.fillRect(x + px * 5, y + py * 5, 5, 5);
            }
        }
        String letter = acc == null ? "?" : acc.name.substring(0, 1);
        PixelFont.draw(g, letter, x + 20 - PixelFont.width(letter, 3) / 2, y + 10, 3, Color.WHITE, true);
    }

    // ------------------------------------------------------------------ overlays

    private Rectangle modal(Graphics2D g, int w, int h, String title) {
        g.setColor(new Color(0, 0, 0, 170));
        g.fillRect(0, 0, W, H);
        Rectangle r = new Rectangle((W - w) / 2, (H - h) / 2, w, h);
        bevel(g, r.x, r.y, r.width, r.height, PANEL, LIGHT, DARK);
        g.setColor(EMBER);
        g.fillRect(r.x + 3, r.y + 3, r.width - 6, 2);
        PixelFont.draw(g, title, r.x + 20, r.y + 20, 2, TEXT, true);
        hits.add(new Hit(new Rectangle(0, 0, W, H), null)); // swallow clicks behind the modal
        return r;
    }

    private void drawAccounts(Graphics2D g) {
        Rectangle m = modal(g, 460, 340, "ACCOUNTS");
        int by = m.y + 178;
        int x = m.x + 20;
        int y = m.y + 50;
        g.setFont(body);
        for (Account a : new ArrayList<>(accounts.all())) {
            Rectangle row = new Rectangle(x, y, m.width - 40, 34);
            boolean sel = a == accounts.selected();
            boolean h = row.contains(mouseX, mouseY);
            fill(g, row, sel ? new Color(0x2A1D14) : h ? new Color(0x26262E) : new Color(0x18181D));
            if (sel) {
                g.setColor(EMBER);
                g.fillRect(row.x, row.y, 3, row.height);
            }
            g.setColor(TEXT);
            g.setFont(bodyBold);
            g.drawString(a.name, row.x + 12, row.y + 15);
            g.setFont(small);
            g.setColor(a.isPremium() ? EMBER_LIGHT : DIM);
            g.drawString(a.isPremium() ? "Microsoft" : "Offline", row.x + 12, row.y + 29);
            Rectangle del = new Rectangle(row.x + row.width - 30, row.y + 7, 20, 20);
            boolean hd = del.contains(mouseX, mouseY);
            g.setColor(hd ? RED : MUTED);
            for (int i = 0; i < 8; i += 2) {
                g.fillRect(del.x + 6 + i, del.y + 6 + i, 2, 2);
                g.fillRect(del.x + 12 - i, del.y + 6 + i, 2, 2);
            }
            hits.add(new Hit(row, () -> {
                accounts.select(a);
                overlay = Overlay.NONE;
            }));
            hits.add(new Hit(del, () -> accounts.remove(a)));
            y += 40;
            if (y > by - 50) {
                break;
            }
        }
        if (accounts.all().isEmpty()) {
            g.setFont(body);
            g.setColor(DIM);
            g.drawString("Add an account to play.", x, y + 14);
            y += 30;
        }

        Rectangle ms = new Rectangle(x, by, m.width - 40, 36);
        boolean hm = ms.contains(mouseX, mouseY);
        bevel(g, ms.x, ms.y, ms.width, ms.height, hm ? new Color(0x2C2C36) : new Color(0x24242C), LIGHT, DARK);
        g.setFont(bodyBold);
        g.setColor(TEXT);
        drawCentered(g, "Sign in with Microsoft (premium)", ms);
        hits.add(new Hit(ms, this::startMicrosoft));

        g.setFont(small);
        g.setColor(DIM);
        g.drawString("or play offline on non-premium servers:", x, by + 58);
        nickField.setBounds(x, by + 66, m.width - 40 - 96, 30);
        Rectangle add = new Rectangle(x + m.width - 40 - 88, by + 66, 88, 30);
        boolean ha = add.contains(mouseX, mouseY);
        bevel(g, add.x, add.y, add.width, add.height, ha ? new Color(0xFF7A14) : EMBER, EMBER_LIGHT, new Color(0x6E2604));
        g.setFont(bodyBold);
        g.setColor(Color.WHITE);
        drawCentered(g, "Add", add);
        hits.add(new Hit(add, this::addOffline));

        if (!accounts.all().isEmpty()) {
            Rectangle close = new Rectangle(m.x + m.width - 34, m.y + 14, 20, 20);
            g.setColor(close.contains(mouseX, mouseY) ? TEXT : DIM);
            for (int i = 0; i < 8; i += 2) {
                g.fillRect(close.x + 6 + i, close.y + 6 + i, 2, 2);
                g.fillRect(close.x + 12 - i, close.y + 6 + i, 2, 2);
            }
            hits.add(new Hit(close, () -> overlay = Overlay.NONE));
        }
        if (statusError && overlay == Overlay.ACCOUNTS) {
            g.setFont(small);
            g.setColor(RED);
            for (String line : wrap(g.getFontMetrics(), status, m.width - 40)) {
                g.drawString(line, x, m.y + m.height - 22);
                break;
            }
        }
    }

    private void drawDeviceCode(Graphics2D g) {
        Rectangle m = modal(g, 460, 300, "MICROSOFT");
        int x = m.x + 20;
        g.setFont(body);
        g.setColor(DIM);
        MicrosoftAuth.DeviceCode dc = deviceCode;
        if (dc == null) {
            g.drawString("Contacting Microsoft...", x, m.y + 70);
        } else {
            g.drawString("1. Open the Microsoft page (button below)", x, m.y + 66);
            g.drawString("2. Type this code and sign in:", x, m.y + 86);
            int cw = PixelFont.width(dc.userCode, 4);
            Rectangle codeBox = new Rectangle(m.x + (m.width - cw) / 2 - 16, m.y + 102, cw + 32, 48);
            bevel(g, codeBox.x, codeBox.y, codeBox.width, codeBox.height, new Color(0x101013), DARK, LIGHT);
            PixelFont.draw(g, dc.userCode, codeBox.x + 16, codeBox.y + 10, 4, EMBER_LIGHT, true);

            Rectangle openB = new Rectangle(x, m.y + 168, 200, 34);
            Rectangle copyB = new Rectangle(x + 210, m.y + 168, m.width - 40 - 210, 34);
            button(g, openB, "Open microsoft.com/link", () -> {
                copy(dc.userCode);
                open(dc.verificationUri);
            });
            button(g, copyB, "Copy code", () -> copy(dc.userCode));
        }
        g.setFont(small);
        g.setColor(statusError ? RED : DIM);
        int sy = m.y + 228;
        for (String line : wrap(g.getFontMetrics(), status, m.width - 40)) {
            g.drawString(line, x, sy);
            sy += 15;
        }
        Rectangle cancel = new Rectangle(m.x + m.width - 110, m.y + m.height - 40, 90, 26);
        button(g, cancel, statusError ? "Back" : "Cancel", () -> {
            cancelLogin = true;
            statusError = false;
            status = "";
            overlay = Overlay.ACCOUNTS;
        });
    }

    // ------------------------------------------------------------------ actions

    private void addOffline() {
        String nick = nickField.getText().trim();
        if (!Account.validNick(nick)) {
            status = "Nick must be 3-16 characters: letters, numbers and _";
            statusError = true;
            repaint();
            return;
        }
        accounts.put(Account.offline(nick));
        nickField.setText("");
        status = "";
        statusError = false;
        overlay = Overlay.NONE;
        repaint();
    }

    private void startMicrosoft() {
        overlay = Overlay.DEVICE_CODE;
        deviceCode = null;
        cancelLogin = false;
        status = "";
        statusError = false;
        new Thread(() -> {
            MicrosoftAuth auth = new MicrosoftAuth();
            try {
                deviceCode = auth.startDeviceCode();
                status = "Waiting for you to sign in...";
                repaintLater();
                Account a = auth.waitForLogin(deviceCode, () -> cancelLogin);
                accounts.put(a);
                status = "";
                overlay = Overlay.NONE;
            } catch (Exception e) {
                if (!cancelLogin) {
                    status = e instanceof MicrosoftAuth.AuthException ? e.getMessage() : "Sign-in failed: " + e.getMessage();
                    statusError = true;
                }
            }
            repaintLater();
        }, "ms-login").start();
    }

    private void play() {
        Account acc = accounts.selected();
        if (acc == null) {
            overlay = Overlay.ACCOUNTS;
            return;
        }
        busy = true;
        statusError = false;
        new Thread(() -> {
            try {
                progress = -1;
                setStatus("Checking for Ignix updates...");
                java.io.File jar = updater.ensureClient(this::onProgress);
                GameInstaller.Installed inst = installer.install(this::onProgress);
                Account a = acc;
                if (a.isPremium()) {
                    setStatus("Refreshing your Microsoft login...");
                    a = new MicrosoftAuth().refresh(a);
                    accounts.put(a);
                }
                setStatus("Starting Minecraft...");
                progress = 1;
                List<String> cmd = GameLauncher.buildCommand(installer.getVersion(), inst, jar, a, settings);
                long started = System.currentTimeMillis();
                Process p = GameLauncher.start(cmd);
                if (settings.hideLauncherWhilePlaying) {
                    Thread.sleep(2500);
                    SwingUtilities.invokeLater(() -> frame.setVisible(false));
                }
                int code = p.waitFor();
                SwingUtilities.invokeLater(() -> frame.setVisible(true));
                if (code != 0 && System.currentTimeMillis() - started < 60_000) {
                    status = "The game closed with an error. Log: .ignix/logs/game-output.log";
                    statusError = true;
                } else {
                    status = "";
                }
            } catch (Exception e) {
                status = e.getMessage() != null ? e.getMessage() : e.toString();
                statusError = true;
            } finally {
                busy = false;
                progress = -1;
                repaintLater();
            }
        }, "play").start();
    }

    private void onProgress(String s, double fraction) {
        status = s;
        progress = fraction;
        repaintLater();
    }

    private void setStatus(String s) {
        status = s;
        repaintLater();
    }

    private void loadNews() {
        IgnixUpdater.Info info = updater.fetchLatest();
        if (info != null) {
            latestVersion = info.version;
            changelog = info.changelog.isEmpty() ? "No notes for this version." : info.changelog;
        } else {
            changelog = "Couldn't load news (offline?). You can still play the last installed version.";
        }
        repaintLater();
    }

    private void setRamFromMouse(int mx, Rectangle r) {
        double f = Math.max(0, Math.min(1, (mx - r.x - 6) / (double) (r.width - 12)));
        int mb = (int) Math.round((1024 + f * (8192 - 1024)) / 512.0) * 512;
        settings.ramMb = Math.max(1024, Math.min(8192, mb));
    }

    // ------------------------------------------------------------------ drawing helpers

    /** Minecraft-style bevel: light top/left edge, dark bottom/right edge, 2px each. */
    private static void bevel(Graphics2D g, int x, int y, int w, int h, Color base, Color light, Color dark) {
        g.setColor(base);
        g.fillRect(x, y, w, h);
        g.setColor(light);
        g.fillRect(x, y, w - 2, 2);
        g.fillRect(x, y, 2, h - 2);
        g.setColor(dark);
        g.fillRect(x + 2, y + h - 2, w - 2, 2);
        g.fillRect(x + w - 2, y + 2, 2, h - 2);
    }

    private static void fill(Graphics2D g, Rectangle r, Color c) {
        g.setColor(c);
        g.fillRect(r.x, r.y, r.width, r.height);
    }

    private void button(Graphics2D g, Rectangle r, String text, Runnable action) {
        boolean h = r.contains(mouseX, mouseY);
        bevel(g, r.x, r.y, r.width, r.height, h ? new Color(0x2C2C36) : new Color(0x24242C), LIGHT, DARK);
        g.setFont(bodyBold);
        g.setColor(TEXT);
        drawCentered(g, text, r);
        hits.add(new Hit(r, action));
    }

    private void link(Graphics2D g, String text, int x, int y, Runnable action) {
        FontMetrics fm = g.getFontMetrics();
        Rectangle r = new Rectangle(x, y - fm.getAscent(), fm.stringWidth(text), fm.getHeight());
        boolean h = r.contains(mouseX, mouseY);
        g.setColor(h ? EMBER : DIM);
        g.drawString(text, x, y);
        if (h) {
            g.fillRect(x, y + 2, fm.stringWidth(text), 1);
        }
        hits.add(new Hit(r, action));
    }

    private static void drawCentered(Graphics2D g, String s, Rectangle r) {
        FontMetrics fm = g.getFontMetrics();
        g.drawString(s, r.x + (r.width - fm.stringWidth(s)) / 2, r.y + (r.height + fm.getAscent() - fm.getDescent()) / 2);
    }

    static List<String> wrap(FontMetrics fm, String text, int width) {
        List<String> out = new ArrayList<>();
        for (String para : text.split("\n")) {
            StringBuilder line = new StringBuilder();
            for (String w : para.split(" ")) {
                String test = line.length() == 0 ? w : line + " " + w;
                if (fm.stringWidth(test) > width && line.length() > 0) {
                    out.add(line.toString());
                    line = new StringBuilder(w);
                } else {
                    line = new StringBuilder(test);
                }
            }
            out.add(line.toString());
        }
        return out;
    }

    private static void open(String url) {
        try {
            Desktop.getDesktop().browse(new URI(url));
        } catch (Exception ignored) {
        }
    }

    private static void copy(String s) {
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(s), null);
        } catch (Exception ignored) {
        }
    }

    private void repaintLater() {
        SwingUtilities.invokeLater(this::repaint);
    }

    private Hit hitAt(int x, int y) {
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit h = hits.get(i);
            if (h.rect.contains(x, y)) {
                return h.action == null && !h.slider ? null : h;
            }
        }
        return null;
    }

    private static final class Hit {
        final Rectangle rect;
        final Runnable action;
        final boolean slider;

        Hit(Rectangle rect, Runnable action) {
            this.rect = rect;
            this.action = action;
            this.slider = false;
        }

        Hit(Rectangle rect, boolean slider) {
            this.rect = rect;
            this.action = null;
            this.slider = slider;
        }
    }

    /** For the build: renders the UI into an image without opening a window. */
    public BufferedImage snapshot() {
        setSize(W, H);
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        paintComponent(g);
        g.dispose();
        return img;
    }

    /** Headless preview for the build (no window, no network). */
    public static BufferedImage preview(String overlayName) {
        LauncherPanel p = new LauncherPanel(null, false);
        p.changelog = "- Own launcher with Microsoft and offline accounts\n- FPS Boost with occlusion culling\n- 1.9 boss bars, combo and reach display\n- Ignix badge next to your nick";
        p.latestVersion = "0.5.0";
        p.overlay = "accounts".equals(overlayName) ? Overlay.ACCOUNTS : Overlay.NONE;
        return p.snapshot();
    }
}
