package com.yanshell.terminal;

import com.jediterm.core.Color;
import com.jediterm.terminal.TerminalColor;
import com.jediterm.terminal.TextStyle;
import com.jediterm.terminal.emulator.ColorPalette;
import com.jediterm.terminal.ui.JediTermWidget;
import com.jediterm.terminal.ui.settings.DefaultSettingsProvider;

import javax.swing.JPanel;
import java.awt.BorderLayout;

/**
 * Swing component that hosts a {@link JediTermWidget}.
 *
 * <p>Display only — does not know about SSH. The outside world plugs in a
 * {@link SshTtyConnector} via {@link #attach(SshTtyConnector)} and the panel
 * starts pumping bytes through it.</p>
 */
public final class TerminalPanel extends JPanel {

    private final JediTermWidget widget;
    private SshTtyConnector connector;

    public TerminalPanel() {
        super(new BorderLayout());
        // Pre-paint the FinalShell dark-blue background so there is no white
        // flash before JediTerm renders its first frame.
        java.awt.Color preFill = new java.awt.Color(0x0C, 0x22, 0x38);
        setBackground(preFill);
        widget = new JediTermWidget(80, 24, new FinalShellSettingsProvider());
        widget.setBackground(preFill);
        add(widget, BorderLayout.CENTER);
    }

    /** Bind a connector and start the read loop. */
    public void attach(SshTtyConnector connector) {
        this.connector = connector;
        widget.setTtyConnector(connector);
        widget.start();
    }

    /** Stop the read loop and unbind. */
    public void detach() {
        if (connector != null) {
            try {
                widget.close();
            } catch (Exception ignored) {
            }
            connector = null;
        }
    }

    public JediTermWidget getWidget() {
        return widget;
    }

    /** Refresh terminal UI after theme change. */
    public void refreshColors() {
        widget.updateUI();
        widget.repaint();
    }

    /**
     * Settings provider with fixed FinalShell-style blue terminal colors.
     * Terminal appearance does NOT follow the system theme.
     */
    private static class FinalShellSettingsProvider extends DefaultSettingsProvider {
        // FinalShell-style dark blue background
        private static final TerminalColor BG = TerminalColor.rgb(0x0C, 0x22, 0x38);
        // White text
        private static final TerminalColor FG = TerminalColor.rgb(0xFF, 0xFF, 0xFF);

        @Override
        public TextStyle getDefaultStyle() {
            // JediTerm renders the terminal's default cell using getDefaultStyle().
            // The library's base default is TextStyle(BLACK, WHITE) → black-on-white;
            // overriding this is what makes the terminal white-on-blue. The
            // getDefaultForeground()/getDefaultBackground() getters derive from this.
            return new TextStyle(FG, BG);
        }

        @Override
        public ColorPalette getTerminalColorPalette() {
            return new FinalShellPalette();
        }
    }

    /**
     * Custom color palette for FinalShell-style terminal.
     * Default colors (null) resolve to blue bg / white fg.
     * ANSI colors are tuned for readability on dark blue background.
     */
    private static class FinalShellPalette extends ColorPalette {
        // Dark blue background
        private static final Color BG = new Color(0x0C, 0x22, 0x38);
        // White foreground
        private static final Color FG = new Color(0xFF, 0xFF, 0xFF);

        // ANSI colors: index 0 = FinalShell dark blue (default bg), index 7 = white (default fg)
        private static final Color[] COLORS = {
            // 0-7: normal colors
            new Color(0x0C, 0x22, 0x38),  // 0 black → FinalShell dark blue (default background)
            new Color(0xCD, 0x31, 0x31),  // 1 red
            new Color(0x0D, 0xBC, 0x79),  // 2 green
            new Color(0xE5, 0xE5, 0x10),  // 3 yellow
            new Color(0x24, 0x72, 0xC8),  // 4 blue
            new Color(0xBC, 0x3F, 0xBC),  // 5 magenta
            new Color(0x11, 0xA8, 0xCD),  // 6 cyan
            new Color(0xFF, 0xFF, 0xFF),  // 7 white → pure white (default foreground)
            // 8-15: bright colors
            new Color(0x66, 0x66, 0x66),  // 8 bright black (gray)
            new Color(0xF1, 0x4C, 0x4C),  // 9 bright red
            new Color(0x23, 0xD1, 0x86),  // 10 bright green
            new Color(0xF5, 0xF5, 0x43),  // 11 bright yellow
            new Color(0x3B, 0x8E, 0xEE),  // 12 bright blue
            new Color(0xD6, 0x70, 0xD6),  // 13 bright magenta
            new Color(0x29, 0xB8, 0xDB),  // 14 bright cyan
            new Color(0xFF, 0xFF, 0xFF),  // 15 bright white
        };

        @Override
        protected Color getForegroundByColorIndex(int index) {
            if (index >= 0 && index < COLORS.length) {
                return COLORS[index];
            }
            return FG;
        }

        @Override
        protected Color getBackgroundByColorIndex(int index) {
            if (index >= 0 && index < COLORS.length) {
                return COLORS[index];
            }
            return BG;
        }

        @Override
        public Color getForeground(TerminalColor color) {
            if (color == null) return FG;
            return super.getForeground(color);
        }

        @Override
        public Color getBackground(TerminalColor color) {
            if (color == null) return BG;
            return super.getBackground(color);
        }
    }
}
