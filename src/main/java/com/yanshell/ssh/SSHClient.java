package com.yanshell.ssh;

import com.yanshell.core.TerminalInput;
import com.yanshell.core.TerminalOutput;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.channel.ChannelShell;
import org.apache.sshd.client.future.AuthFuture;
import org.apache.sshd.client.future.ConnectFuture;
import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.config.keys.FilePasswordProvider;
import org.apache.sshd.common.util.io.resource.PathResource;
import org.apache.sshd.common.util.security.SecurityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyPair;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns the SSH client / session / shell channel.
 *
 * <p>Pure network layer — never touches Swing. All methods are safe to call
 * from a background thread; {@link #connect()} blocks until the shell is open.</p>
 */
public final class SSHClient implements TerminalInput {

    private static final Logger log = LoggerFactory.getLogger(SSHClient.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private final SSHConfig config;
    private final KnownHostsVerifier.HostKeyPrompt hostKeyPrompt;
    private final AtomicBoolean connected = new AtomicBoolean(false);

    private TerminalOutput output;
    private SshClient client;
    private ClientSession session;
    private ChannelShell channel;
    private OutputStream channelIn;

    public SSHClient(SSHConfig config) {
        this(config, null);
    }

    /**
     * @param config        SSH connection parameters
     * @param hostKeyPrompt callback for unknown/changed host keys;
     *                      pass {@code null} to accept all keys (insecure!)
     */
    public SSHClient(SSHConfig config, KnownHostsVerifier.HostKeyPrompt hostKeyPrompt) {
        this.config = config;
        this.hostKeyPrompt = hostKeyPrompt;
    }

    /**
     * Output sink for bytes coming back from the remote PTY.
     * Must be set before {@link #connect()}.
     */
    public void setOutput(TerminalOutput output) {
        this.output = output;
    }

    public void connect() throws IOException {
        if (output == null) {
            throw new IllegalStateException("TerminalOutput must be set before connect()");
        }

        client = SshClient.setUpDefaultClient();
        if (hostKeyPrompt != null) {
            client.setServerKeyVerifier(new KnownHostsVerifier(hostKeyPrompt));
        } else {
            client.setServerKeyVerifier(AcceptAllServerKeyVerifier.INSTANCE);
        }
        client.start();

        try {
            ConnectFuture connectFuture =
                    client.connect(config.getUsername(), config.getHost(), config.getPort());
            session = connectFuture.verify(TIMEOUT).getSession();

            if (config.hasPrivateKey()) {
                loadPrivateKeyIdentity();
            }
            if (config.hasPassword()) {
                session.addPasswordIdentity(config.getPassword());
            }

            AuthFuture auth = session.auth();
            auth.verify(TIMEOUT);

            channel = session.createShellChannel();
            channel.setPtyType("xterm-256color");
            channel.setPtyColumns(80);
            channel.setPtyLines(24);
            channel.setUsePty(true);

            // Forward remote stdout / stderr straight into the terminal sink.
            OutputStream sink = new OutputStream() {
                @Override
                public void write(int b) {
                    output.write(new byte[]{(byte) b});
                }

                @Override
                public void write(byte[] b, int off, int len) {
                    byte[] copy = new byte[len];
                    System.arraycopy(b, off, copy, 0, len);
                    output.write(copy);
                }
            };
            channel.setOut(sink);
            channel.setErr(sink);

            channel.open().verify(TIMEOUT);
            // Stream we push keystrokes into.
            channelIn = channel.getInvertedIn();
            connected.set(true);

            log.info("SSH connected: {}@{}:{}",
                    config.getUsername(), config.getHost(), config.getPort());
        } catch (Exception e) {
            disconnect();
            throw new IOException("SSH connect failed: " + e.getMessage(), e);
        }
    }

    private void loadPrivateKeyIdentity() throws IOException {
        Path keyPath = Paths.get(config.getPrivateKeyPath());
        if (!Files.isRegularFile(keyPath)) {
            throw new IOException("Private key not found: " + keyPath);
        }
        FilePasswordProvider passwordProvider =
                (config.getKeyPassphrase() != null && !config.getKeyPassphrase().isEmpty())
                        ? FilePasswordProvider.of(config.getKeyPassphrase())
                        : FilePasswordProvider.EMPTY;

        try (InputStream in = Files.newInputStream(keyPath)) {
            Iterable<KeyPair> keys = SecurityUtils.loadKeyPairIdentities(
                    session, new PathResource(keyPath), in, passwordProvider);
            if (keys == null) {
                throw new IOException("No key pairs found in " + keyPath);
            }
            int count = 0;
            for (KeyPair kp : keys) {
                session.addPublicKeyIdentity(kp);
                count++;
            }
            if (count == 0) {
                throw new IOException("No key pairs loaded from " + keyPath);
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Failed to load private key: " + e.getMessage(), e);
        }
    }

    @Override
    public void send(byte[] data) {
        if (!connected.get() || channelIn == null) {
            return;
        }
        try {
            channelIn.write(data);
            channelIn.flush();
        } catch (IOException e) {
            log.warn("send failed: {}", e.getMessage());
        }
    }

    /**
     * Send SIGWINCH to the remote PTY so that programs like vim / less repaint
     * to the new size.
     */
    public void resize(int columns, int rows) {
        if (!connected.get() || channel == null) {
            return;
        }
        try {
            channel.sendWindowChange(columns, rows);
        } catch (IOException e) {
            log.warn("resize failed: {}", e.getMessage());
        }
    }

    public boolean isConnected() {
        return connected.get();
    }

    /**
     * Underlying client session. Exposed so the SFTP layer can open its own
     * subsystem channel on the same authenticated connection.
     * Returns {@code null} when not connected.
     */
    public org.apache.sshd.client.session.ClientSession getSession() {
        return session;
    }

    public void disconnect() {
        connected.set(false);

        // Close in dependency order: channel → session → client. The trick
        // is that the NIO async group inside SshClient still has callbacks
        // in flight; if we call client.stop() while those land, the post-
        // completion executor is gone and you get
        //   IllegalStateException: Executor has been shut down
        // on a daemon thread. Using the SshdCloseable graceful close
        // (close(false)) drains the I/O before tearing the executor down.
        try {
            if (channel != null) channel.close(false).await(java.time.Duration.ofSeconds(2));
        } catch (Exception ignored) {
        }
        try {
            if (session != null) session.close(false).await(java.time.Duration.ofSeconds(2));
        } catch (Exception ignored) {
        }
        if (client != null) {
            try {
                // Graceful close first — lets pending completion handlers run.
                client.close(false).await(java.time.Duration.ofSeconds(2));
            } catch (Exception e) {
                log.debug("client graceful close: {}", e.getMessage());
            }
            try {
                client.stop();
            } catch (Exception e) {
                log.debug("client.stop() threw: {}", e.getMessage());
            }
        }

        channel = null;
        session = null;
        client = null;
        channelIn = null;
        log.info("SSH disconnected");
    }
}
