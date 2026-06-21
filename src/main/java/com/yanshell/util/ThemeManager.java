package com.yanshell.util;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatDarculaLaf;
import com.formdev.flatlaf.FlatIntelliJLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;
import com.formdev.flatlaf.themes.FlatMacDarkLaf;
import com.formdev.flatlaf.themes.FlatMacLightLaf;
import com.yanshell.core.AppSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Manages FlatLaf theme switching and persistence.
 */
public class ThemeManager {

    private static final Logger log = LoggerFactory.getLogger(ThemeManager.class);
    private static final ThemeManager INSTANCE = new ThemeManager();

    /** Theme class name -> display name */
    private static final Map<String, String> THEMES = new LinkedHashMap<>();

    static {
        THEMES.put("FlatLightLaf", "FlatLaf Light");
        THEMES.put("FlatDarkLaf", "FlatLaf Dark");
        THEMES.put("FlatIntelliJLaf", "IntelliJ Light");
        THEMES.put("FlatDarculaLaf", "Darcula");
        THEMES.put("FlatMacLightLaf", "macOS Light");
        THEMES.put("FlatMacDarkLaf", "macOS Dark");
    }

    private AppSettings settings;
    private String currentTheme;

    private ThemeManager() {
        settings = AppSettings.load();
        currentTheme = settings.getThemeClassName();
    }

    public static ThemeManager getInstance() {
        return INSTANCE;
    }

    /** Returns map of theme class name -> display name. */
    public Map<String, String> getAvailableThemes() {
        return THEMES;
    }

    /** Returns the current theme class name. */
    public String getCurrentTheme() {
        return currentTheme;
    }

    /** Returns the display name for the current theme. */
    public String getCurrentThemeName() {
        return THEMES.getOrDefault(currentTheme, currentTheme);
    }

    /**
     * Apply the theme and save preference.
     *
     * @param themeClassName short class name (e.g. "FlatDarkLaf")
     * @return true if successful
     */
    public boolean applyTheme(String themeClassName) {
        try {
            FlatLaf theme = createTheme(themeClassName);
            if (theme == null) {
                log.error("Unknown theme: {}", themeClassName);
                return false;
            }

            FlatLaf.setup(theme);
            currentTheme = themeClassName;
            settings.setThemeClassName(themeClassName);
            settings.save();

            // Update all existing windows
            updateAllWindows();
            log.info("Theme applied: {} ({})", THEMES.get(themeClassName), themeClassName);
            return true;
        } catch (Exception e) {
            log.error("Failed to apply theme {}: {}", themeClassName, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Apply the saved theme at startup.
     */
    public void applySavedTheme() {
        applyTheme(currentTheme);
    }

    private FlatLaf createTheme(String className) {
        return switch (className) {
            case "FlatLightLaf" -> new FlatLightLaf();
            case "FlatDarkLaf" -> new FlatDarkLaf();
            case "FlatIntelliJLaf" -> new FlatIntelliJLaf();
            case "FlatDarculaLaf" -> new FlatDarculaLaf();
            case "FlatMacLightLaf" -> new FlatMacLightLaf();
            case "FlatMacDarkLaf" -> new FlatMacDarkLaf();
            default -> null;
        };
    }

    private void updateAllWindows() {
        for (Window window : Window.getWindows()) {
            SwingUtilities.updateComponentTreeUI(window);
        }
    }
}
