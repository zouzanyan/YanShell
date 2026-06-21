package com.yanshell.terminal;

import com.jediterm.core.util.TermSize;
import com.jediterm.terminal.Questioner;
import com.jediterm.terminal.TtyConnector;
import com.yanshell.ssh.SSHClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;

/**
 * Bridge between {@link SSHClient} (raw bytes) and JediTerm (chars).
 *
 * <p>Bytes the SSH layer hands us via {@link #feed(byte[])} are pumped through a
 * piped stream and decoded as UTF-8 by an {@link InputStreamReader}. JediTerm
 * pulls characters from that reader on its own thread; multi-byte boundaries
 * are handled correctly by the reader.</p>
 */
public final class SshTtyConnector implements TtyConnector {

    private static final Logger log = LoggerFactory.getLogger(SshTtyConnector.class);

    private final SSHClient sshClient;
    private final PipedInputStream rawIn;
    private final PipedOutputStream rawOut;
    private final InputStreamReader reader;
    private final CountDownLatch closeLatch = new CountDownLatch(1);

    private volatile boolean closed = false;

    public SshTtyConnector(SSHClient sshClient) {
        this.sshClient = sshClient;
        try {
            this.rawIn = new PipedInputStream(64 * 1024);
            this.rawOut = new PipedOutputStream(rawIn);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to wire piped streams", e);
        }
        this.reader = new InputStreamReader(rawIn, StandardCharsets.UTF_8);
    }

    /** Called by the SSH layer when bytes arrive from the remote PTY. */
    public void feed(byte[] data) {
        if (closed) {
            return;
        }
        try {
            rawOut.write(data);
            rawOut.flush();
        } catch (IOException e) {
            log.debug("feed dropped {} bytes: {}", data.length, e.getMessage());
        }
    }

    @Override
    public boolean init(Questioner q) {
        return sshClient.isConnected();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            rawOut.close();
        } catch (IOException ignored) {
        }
        try {
            rawIn.close();
        } catch (IOException ignored) {
        }
        sshClient.disconnect();
        closeLatch.countDown();
    }

    @Override
    public String getName() {
        return "ssh";
    }

    @Override
    public int read(char[] buf, int offset, int length) throws IOException {
        return reader.read(buf, offset, length);
    }

    @Override
    public void write(byte[] bytes) throws IOException {
        sshClient.send(bytes);
    }

    @Override
    public void write(String string) throws IOException {
        write(string.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public boolean isConnected() {
        return !closed && sshClient.isConnected();
    }

    @Override
    public int waitFor() throws InterruptedException {
        closeLatch.await();
        return 0;
    }

    @Override
    public boolean ready() throws IOException {
        return reader.ready();
    }

    @Override
    public void resize(TermSize termSize) {
        sshClient.resize(termSize.getColumns(), termSize.getRows());
    }
}
