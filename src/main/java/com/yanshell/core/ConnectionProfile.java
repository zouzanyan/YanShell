package com.yanshell.core;

import com.yanshell.ssh.SSHConfig;

/**
 * Leaf node in the connection tree — the persisted SSH configuration that
 * the user can double-click to open a session. Pure configuration; nothing
 * run-time (channel, terminal buffer, sftp client) lives here.
 */
public final class ConnectionProfile implements ConnectionNode {

    private String name;
    private SSHConfig config;

    public ConnectionProfile(String name, SSHConfig config) {
        this.name = name;
        this.config = config;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public void setName(String name) {
        this.name = name;
    }

    public SSHConfig getConfig() {
        return config;
    }

    public void setConfig(SSHConfig config) {
        this.config = config;
    }

    /** Subtitle shown next to the name in the tree. */
    public String describe() {
        return config.getUsername() + "@" + config.getHost() + ":" + config.getPort();
    }
}
