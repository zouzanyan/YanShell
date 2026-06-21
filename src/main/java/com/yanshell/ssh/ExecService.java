package com.yanshell.ssh;

import org.apache.sshd.client.channel.ChannelExec;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.session.ClientSession;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.EnumSet;

/**
 * Runs one-shot commands on the remote host via SSH exec channels.
 *
 * <p>Reuses the authenticated {@link ClientSession} from {@link SSHClient}
 * (same pattern as {@code SftpService}). Each {@link #exec} call opens a
 * fresh {@link ChannelExec}, streams stdout into a buffer, and returns
 * the result as a string.</p>
 */
public final class ExecService implements AutoCloseable {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private final ClientSession session;

    public ExecService(SSHClient sshClient) throws IOException {
        ClientSession s = sshClient.getSession();
        if (s == null || !sshClient.isConnected()) {
            throw new IOException("SSH not connected");
        }
        this.session = s;
    }

    /**
     * Execute a command and return its stdout as a string.
     * Blocks until the command finishes or the timeout elapses.
     */
    public String exec(String command) throws IOException {
        ChannelExec channel = session.createExecChannel(command);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream(8192);
        try {
            channel.setOut(stdout);
            channel.setErr(new ByteArrayOutputStream());
            channel.open().verify(TIMEOUT);
            channel.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), TIMEOUT);
            return stdout.toString(StandardCharsets.UTF_8);
        } finally {
            try {
                channel.close(false).await(TIMEOUT);
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public void close() {
        // Nothing to close — exec channels are ephemeral.
    }
}
