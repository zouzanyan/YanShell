package com.yanshell.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM encryption for sensitive configuration fields (passwords,
 * key passphrases).
 *
 * <h3>Key management</h3>
 * On first use, this class reads (or creates) a 256-bit random key at
 * {@code ~/.yanshell/.key}. If the key file is missing or unreadable a
 * fresh key is generated — previously encrypted data will be unreadable
 * after that (decryption returns {@code null}).
 *
 * <h3>Wire format</h3>
 * Each encrypted field is stored as {@code base64(iv):base64(ciphertext)}
 * where the IV is 12 random bytes and the ciphertext includes the 128-bit
 * GCM authentication tag appended by the JCE provider.
 *
 * <h3>Thread safety</h3>
 * Key initialisation is guarded by a static lock. Encryption / decryption
 * are stateless beyond the shared key and are safe to call concurrently.
 */
public final class CryptoUtil {

    private static final Logger log = LoggerFactory.getLogger(CryptoUtil.class);

    private static final String DIR_NAME  = ".yanshell";
    private static final String KEY_FILE  = ".key";

    private static final int    AES_KEY_BITS = 256;
    private static final int    GCM_IV_BYTES = 12;
    private static final int    GCM_TAG_BITS = 128;

    /** Lazily initialised; never changes after first successful load/generate. */
    private static volatile SecretKeySpec key;
    private static final Object keyLock = new Object();

    private CryptoUtil() {}

    // ---- public API --------------------------------------------------------

    /**
     * Encrypt a plaintext string.
     *
     * @param plaintext the value to encrypt (may be null or empty).
     * @return {@code base64(iv):base64(ciphertext)}, or {@code null} if the
     *         input is null / empty or encryption fails.
     */
    public static String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) return null;
        ensureKey();
        if (key == null) return null;

        try {
            byte[] iv = new byte[GCM_IV_BYTES];
            new SecureRandom().nextBytes(iv);
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_BITS, iv);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, spec);
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            return Base64.getEncoder().encodeToString(iv) + ":" +
                   Base64.getEncoder().encodeToString(ciphertext);
        } catch (Exception e) {
            log.warn("Encryption failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Decrypt a string produced by {@link #encrypt(String)}.
     *
     * @param encoded the {@code base64(iv):base64(ciphertext)} string
     *                (may be null or empty).
     * @return the original plaintext, or {@code null} if the input is
     *         null / empty or decryption fails.
     */
    public static String decrypt(String encoded) {
        if (encoded == null || encoded.isEmpty()) return null;
        ensureKey();
        if (key == null) return null;

        try {
            int colon = encoded.indexOf(':');
            if (colon < 0) return null;          // legacy cleartext or malformed

            byte[] iv         = Base64.getDecoder().decode(encoded.substring(0, colon));
            byte[] ciphertext = Base64.getDecoder().decode(encoded.substring(colon + 1));

            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_BITS, iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, spec);
            byte[] plain = cipher.doFinal(ciphertext);
            return new String(plain, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.debug("Decryption failed (possibly wrong key or corrupted data): {}", e.getMessage());
            return null;
        }
    }

    // ---- key management ----------------------------------------------------

    private static void ensureKey() {
        if (key != null) return;
        synchronized (keyLock) {
            if (key != null) return;
            Path keyPath = Paths.get(System.getProperty("user.home", "."), DIR_NAME, KEY_FILE);
            key = loadOrGenerateKey(keyPath);
        }
    }

    private static SecretKeySpec loadOrGenerateKey(Path keyPath) {
        // 1. Try loading an existing key.
        if (Files.isRegularFile(keyPath)) {
            try {
                byte[] raw = Base64.getDecoder().decode(Files.readString(keyPath).trim());
                if (raw.length == AES_KEY_BITS / 8) {
                    log.debug("Loaded encryption key from {}", keyPath);
                    return new SecretKeySpec(raw, "AES");
                }
                log.warn("Key file {} has wrong length ({} bytes), regenerating",
                        keyPath, raw.length);
            } catch (Exception e) {
                log.warn("Failed to read key file {}: {}", keyPath, e.getMessage());
            }
        }

        // 2. Generate a new key and persist it.
        try {
            Files.createDirectories(keyPath.getParent());
            KeyGenerator gen = KeyGenerator.getInstance("AES");
            gen.init(AES_KEY_BITS, new SecureRandom());
            SecretKey sk = gen.generateKey();
            byte[] raw = sk.getEncoded();
            Files.writeString(keyPath, Base64.getEncoder().encodeToString(raw));
            // Restrict permissions on POSIX; best-effort on Windows.
            try {
                Files.setAttribute(keyPath, "dos:readonly", true);
            } catch (Exception ignored) {}
            log.info("Generated new encryption key at {}", keyPath);
            return new SecretKeySpec(raw, "AES");
        } catch (Exception e) {
            log.error("Failed to generate encryption key: {}", e.getMessage());
            return null;
        }
    }

    // ---- package-visible helpers for testing -------------------------------

    static void resetKey() {
        synchronized (keyLock) {
            key = null;
        }
    }
}
