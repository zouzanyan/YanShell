package com.yanshell.ssh;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Manages a minimal known_hosts file in OpenSSH-compatible format.
 *
 * <p>File location: {@code ~/.yanshell/known_hosts}. Each line is:</p>
 * <pre>
 *   hostname,ip algorithm base64-encoded-public-key
 * </pre>
 *
 * <p>This is deliberately simple — it stores one entry per host+port, no
 * wildcards, no markers, no revocations. Enough to prevent MITM attacks
 * for a desktop SSH client.</p>
 */
public final class KnownHostsStore {

    private static final Logger log = LoggerFactory.getLogger(KnownHostsStore.class);

    private static final String DIR_NAME = ".yanshell";
    private static final String FILE_NAME = "known_hosts";

    private final Path file;

    public KnownHostsStore() {
        this(Paths.get(System.getProperty("user.home", "."), DIR_NAME, FILE_NAME));
    }

    KnownHostsStore(Path file) {
        this.file = file;
    }

    /**
     * Result of checking a host key against the store.
     */
    public enum Status {
        /** Host+port not in the store — first connection. */
        UNKNOWN,
        /** Key matches the stored entry. */
        MATCH,
        /** Host exists but key differs — possible MITM or server reinstalled. */
        CHANGED
    }

    /**
     * Check whether a host key is known and matches.
     */
    public Status check(String host, int port, PublicKey key) {
        String encoded = encodeKey(key);
        if (encoded == null) return Status.UNKNOWN;

        String searchPrefix = hostEntry(host, port) + " ";
        for (Entry e : loadAll()) {
            if (e.line.startsWith(searchPrefix)) {
                if (e.line.endsWith(" " + encoded)) {
                    return Status.MATCH;
                }
                return Status.CHANGED;
            }
        }
        return Status.UNKNOWN;
    }

    /**
     * Add or replace a host key entry. Writes atomically.
     */
    public void add(String host, int port, PublicKey key) {
        String encoded = encodeKey(key);
        if (encoded == null) return;

        String newLine = hostEntry(host, port) + " " + key.getAlgorithm() + " " + encoded;
        String searchPrefix = hostEntry(host, port) + " ";

        List<Entry> entries = loadAll();
        boolean replaced = false;

        try {
            Files.createDirectories(file.getParent());
        } catch (IOException e) {
            log.warn("Failed to create {}: {}", file.getParent(), e.getMessage());
            return;
        }

        Path tmp = file.resolveSibling(file.getFileName().toString() + ".tmp");
        try (BufferedWriter bw = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            for (Entry e : entries) {
                if (e.line.startsWith(searchPrefix)) {
                    bw.write(newLine);
                    replaced = true;
                } else {
                    bw.write(e.line);
                }
                bw.newLine();
            }
            if (!replaced) {
                bw.write(newLine);
                bw.newLine();
            }
            bw.flush();
            Files.move(tmp, file,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.warn("Failed to write {}: {}", file, e.getMessage());
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {}
        }
    }

    // ---- internals -------------------------------------------------------

    private record Entry(String line) {}

    private List<Entry> loadAll() {
        List<Entry> out = new ArrayList<>();
        if (!Files.isRegularFile(file)) return out;
        try (BufferedReader br = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = br.readLine()) != null) {
                if (!line.isBlank() && !line.startsWith("#")) {
                    out.add(new Entry(line));
                }
            }
        } catch (IOException e) {
            log.debug("Failed to read {}: {}", file, e.getMessage());
        }
        return out;
    }

    private static String hostEntry(String host, int port) {
        if (port == 22) return host;
        return "[" + host + "]:" + port;
    }

    private static String encodeKey(PublicKey key) {
        if (key == null) return null;
        return Base64.getEncoder().encodeToString(key.getEncoded());
    }

    /**
     * Format a human-readable fingerprint like {@code SHA256:abc123...}.
     * Uses the same format as OpenSSH 6.8+.
     */
    public static String fingerprint(PublicKey key) {
        if (key == null) return "(unknown)";
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(key.getEncoded());
            String b64 = Base64.getEncoder().encodeToString(digest).replace("=", "");
            return "SHA256:" + b64;
        } catch (Exception e) {
            return key.getAlgorithm() + " " + key.hashCode();
        }
    }
}
