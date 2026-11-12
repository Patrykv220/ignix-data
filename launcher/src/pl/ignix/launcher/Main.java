package pl.ignix.launcher;

import pl.ignix.launcher.ui.LauncherPanel;

import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.awt.Image;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

public final class Main {

    public static void main(String[] args) throws Exception {
        if (args.length == 2 && "--preview".equals(args[0])) {
            System.setProperty("java.awt.headless", "true");
            ImageIO.write(LauncherPanel.preview(null), "png", new File(args[1] + "-main.png"));
            ImageIO.write(LauncherPanel.preview("accounts"), "png", new File(args[1] + "-accounts.png"));
            return;
        }
        System.setProperty("sun.java2d.uiScale.enabled", "true");
        SwingUtilities.invokeLater(() -> {
            JFrame f = new JFrame("Ignix Launcher");
            f.setUndecorated(true);
            f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            List<Image> icons = new ArrayList<>();
            for (String n : new String[]{"/ignix/flame16.png", "/ignix/icon32.png", "/ignix/icon64.png"}) {
                try {
                    icons.add(ImageIO.read(Main.class.getResource(n)));
                } catch (Exception ignored) {
                }
            }
            f.setIconImages(icons);
            LauncherPanel p = new LauncherPanel(f);
            p.setPreferredSize(new java.awt.Dimension(900, 540));
            f.setContentPane(p);
            f.pack();
            f.setResizable(false);
            f.setLocationRelativeTo(null);
            f.setVisible(true);
        });
    }
}
