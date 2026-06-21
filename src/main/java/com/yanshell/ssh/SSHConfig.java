package com.yanshell.ssh;

/**
 * Immutable connection parameters for an SSH session.
 * Either {@code password} or {@code privateKeyPath} (or both) may be supplied.
 */
public final class SSHConfig {

    private final String host;
    private final int port;
    private final String username;
    private final String password;
    private final String privateKeyPath;
    private final String keyPassphrase;

    public SSHConfig(String host,
                     int port,
                     String username,
                     String password,
                     String privateKeyPath,
                     String keyPassphrase) {
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
        this.privateKeyPath = privateKeyPath;
        this.keyPassphrase = keyPassphrase;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }

    public String getPrivateKeyPath() {
        return privateKeyPath;
    }

    public String getKeyPassphrase() {
        return keyPassphrase;
    }

    public boolean hasPassword() {
        return password != null && !password.isEmpty();
    }

    public boolean hasPrivateKey() {
        return privateKeyPath != null && !privateKeyPath.isBlank();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SSHConfig other)) return false;
        return port == other.port
                && java.util.Objects.equals(host, other.host)
                && java.util.Objects.equals(username, other.username)
                && java.util.Objects.equals(password, other.password)
                && java.util.Objects.equals(privateKeyPath, other.privateKeyPath)
                && java.util.Objects.equals(keyPassphrase, other.keyPassphrase);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(host, port, username,
                password, privateKeyPath, keyPassphrase);
    }

    @Override
    public String toString() {
        return username + "@" + host + ":" + port;
    }
}
