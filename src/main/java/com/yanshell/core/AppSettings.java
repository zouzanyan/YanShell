package com.yanshell.core;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Application settings with JSON persistence.
 * Stored at {@code ~/.yanshell/settings.json}.
 */
public class AppSettings {

    private static final Logger log = LoggerFactory.getLogger(AppSettings.class);
    private static final Path SETTINGS_DIR = Path.of(System.getProperty("user.home"), ".yanshell");
    private static final Path SETTINGS_FILE = SETTINGS_DIR.resolve("settings.json");
    private static final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    @JsonProperty("theme")
    private String themeClassName = "FlatLightLaf";

    public String getThemeClassName() {
        return themeClassName;
    }

    public void setThemeClassName(String themeClassName) {
        this.themeClassName = themeClassName;
    }

    /**
     * Load settings from disk, or return defaults if file doesn't exist.
     */
    public static AppSettings load() {
        if (Files.exists(SETTINGS_FILE)) {
            try {
                byte[] data = Files.readAllBytes(SETTINGS_FILE);
                return mapper.readValue(data, AppSettings.class);
            } catch (IOException e) {
                log.warn("Failed to load settings, using defaults: {}", e.getMessage());
            }
        }
        return new AppSettings();
    }

    /**
     * Save settings to disk.
     */
    public void save() {
        try {
            Files.createDirectories(SETTINGS_DIR);
            mapper.writeValue(SETTINGS_FILE.toFile(), this);
            log.info("Settings saved to {}", SETTINGS_FILE);
        } catch (IOException e) {
            log.error("Failed to save settings: {}", e.getMessage(), e);
        }
    }
}
