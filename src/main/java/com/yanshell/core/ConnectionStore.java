package com.yanshell.core;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanshell.ssh.SSHConfig;
import com.yanshell.util.CryptoUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Persists the connection tree to {@code ~/.yanshell/connections.json}.
 *
 * <p>Uses Jackson to serialise a clean JSON structure. Sensitive fields
 * (password, key passphrase) are encrypted with AES-256-GCM via
 * {@link CryptoUtil} before being written. Each write is atomic
 * (temp file + rename).</p>
 */
public final class ConnectionStore {

    private static final Logger log = LoggerFactory.getLogger(ConnectionStore.class);

    private static final String DIR_NAME  = ".yanshell";
    private static final String FILE_NAME = "connections.json";

    private static final ObjectMapper mapper = new ObjectMapper();

    private final Path file;

    public ConnectionStore() {
        this(Paths.get(System.getProperty("user.home", "."), DIR_NAME, FILE_NAME));
    }

    ConnectionStore(Path file) {
        this.file = file;
    }

    // ---- DTOs --------------------------------------------------------------

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = FolderDto.class, name = "folder"),
            @JsonSubTypes.Type(value = ProfileDto.class, name = "profile")
    })
    interface NodeDto {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    static final class FolderDto implements NodeDto {
        public String name;
        public List<NodeDto> children = new ArrayList<>();

        FolderDto() {}
        FolderDto(String name) { this.name = name; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static final class ProfileDto implements NodeDto {
        public String name;
        public String host;
        public int    port = 22;
        public String user;
        public String pwd;   // encrypted or null
        public String key;
        public String kpw;   // encrypted or null

        ProfileDto() {}
    }

    // ---- public API --------------------------------------------------------

    /**
     * Load the connection tree. Always returns a non-null root folder
     * (empty if the file does not exist or fails to parse).
     */
    public ConnectionFolder load() {
        if (!Files.isRegularFile(file)) {
            return new ConnectionFolder("");
        }

        try {
            byte[] raw = Files.readAllBytes(file);
            if (raw.length == 0) return new ConnectionFolder("");

            FolderDto rootDto = mapper.readValue(raw, FolderDto.class);
            return folderFromDto(rootDto);
        } catch (IOException e) {
            log.warn("Failed to read {}: {}", file, e.getMessage());
            return new ConnectionFolder("");
        }
    }

    /** Save the tree atomically (temp file + rename), encrypting secrets. */
    public void save(ConnectionFolder root) {
        try {
            Files.createDirectories(file.getParent());
        } catch (IOException e) {
            log.warn("Failed to create {}: {}", file.getParent(), e.getMessage());
            return;
        }

        FolderDto dto = folderToDto(root);
        Path tmp = file.resolveSibling(file.getFileName().toString() + ".tmp");
        try {
            byte[] json = mapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsBytes(dto);
            Files.write(tmp, json);
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

    // ---- model ↔ DTO conversion --------------------------------------------

    private static FolderDto folderToDto(ConnectionFolder f) {
        FolderDto d = new FolderDto(f.getName());
        for (ConnectionNode child : f.getChildren()) {
            if (child instanceof ConnectionFolder cf) {
                d.children.add(folderToDto(cf));
            } else if (child instanceof ConnectionProfile p) {
                d.children.add(profileToDto(p));
            }
        }
        return d;
    }

    private static ProfileDto profileToDto(ConnectionProfile p) {
        SSHConfig c = p.getConfig();
        ProfileDto d = new ProfileDto();
        d.name = p.getName();
        d.host = c.getHost();
        d.port = c.getPort();
        d.user = c.getUsername();
        d.pwd  = CryptoUtil.encrypt(c.getPassword());
        d.key  = c.getPrivateKeyPath();
        d.kpw  = CryptoUtil.encrypt(c.getKeyPassphrase());
        return d;
    }

    private static ConnectionFolder folderFromDto(FolderDto d) {
        ConnectionFolder f = new ConnectionFolder(d.name == null ? "" : d.name);
        if (d.children != null) {
            for (NodeDto child : d.children) {
                if (child instanceof FolderDto fd) {
                    f.getChildren().add(folderFromDto(fd));
                } else if (child instanceof ProfileDto pd) {
                    ConnectionProfile p = profileFromDto(pd);
                    if (p != null) f.getChildren().add(p);
                }
            }
        }
        return f;
    }

    private static ConnectionProfile profileFromDto(ProfileDto d) {
        if (d.host == null || d.host.isEmpty()) return null;
        String password   = CryptoUtil.decrypt(d.pwd);
        String passphrase = CryptoUtil.decrypt(d.kpw);
        SSHConfig cfg = new SSHConfig(
                d.host,
                d.port > 0 && d.port <= 65535 ? d.port : 22,
                d.user != null ? d.user : "",
                password,
                d.key,
                passphrase);
        return new ConnectionProfile(d.name != null ? d.name : "", cfg);
    }
}
