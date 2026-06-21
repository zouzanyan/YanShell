package com.yanshell;

import com.yanshell.ui.MainFrame;
import com.yanshell.util.ThemeManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/**
 * Application entry point.
 * Loads saved theme preference (defaults to FlatLaf Light),
 * then shows the main frame on the EDT.
 */
public final class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    private Main() {
    }

    public static void main(String[] args) {
        // Apply saved theme or default
        try {
            ThemeManager.getInstance().applySavedTheme();
        } catch (Exception e) {
            log.warn("Theme setup failed, falling back to system L&F: {}", e.getMessage());
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
            }
        }

        SwingUtilities.invokeLater(() -> {
            MainFrame frame = new MainFrame();
            frame.setVisible(true);
        });
    }
}
