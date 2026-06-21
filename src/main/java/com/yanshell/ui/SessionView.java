package com.yanshell.ui;

import com.yanshell.core.ConnectionProfile;
import com.yanshell.sftp.SftpService;
import com.yanshell.ssh.ExecService;
import com.yanshell.ssh.SSHClient;
import com.yanshell.terminal.SshTtyConnector;
import com.yanshell.terminal.TerminalPanel;

import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import java.awt.BorderLayout;

/**
 * One open SSH session: terminal + file manager + system monitor bound to
 * a single authenticated {@link SSHClient}. Each session lives in its own
 * tab in the main window's outer session tabbed pane.
 */
public final class SessionView extends JPanel {

    private final ConnectionProfile profile;
    private final SSHClient client;
    private final TerminalPanel terminal = new TerminalPanel();
    private final FileManagerPanel fileManager = new FileManagerPanel();
    private final MonitorPanel monitor = new MonitorPanel();

    public SessionView(ConnectionProfile profile,
                       SSHClient client,
                       SshTtyConnector connector,
                       SftpService sftp,
                       ExecService exec) {
        this.profile = profile;
        this.client = client;

        JTabbedPane inner = new JTabbedPane();
        inner.addTab("终端", terminal);
        inner.addTab("文件管理", fileManager);
        inner.addTab("系统监控", monitor);

        setLayout(new BorderLayout());
        add(inner, BorderLayout.CENTER);

        terminal.attach(connector);
        if (sftp != null) {
            fileManager.attach(sftp);
        }
        if (exec != null) {
            monitor.setHostname(profile.getName());
            monitor.attach(exec);
        }
    }

    public ConnectionProfile getProfile() {
        return profile;
    }

    /** Single-line status text for the bottom bar when this session is active. */
    public String getStatusLine() {
        var c = profile.getConfig();
        return profile.getName() + "  —  " + c.getUsername() + "@" + c.getHost() + ":" + c.getPort();
    }

    /**
     * Tear everything down. Closes SFTP, terminal channel, and SSH session.
     * Safe to call multiple times.
     */
    public void dispose() {
        monitor.detach();
        fileManager.detach();   // closes the SftpService
        terminal.detach();      // closes the JediTerm tty connector
        client.disconnect();
    }
}
