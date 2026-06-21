package com.yanshell.sftp;

import com.yanshell.core.RemoteEntry;
import com.yanshell.ssh.SSHClient;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Thin SFTP wrapper sitting on top of a connected {@link SSHClient}.
 *
 * <p>Network layer only — no Swing. Methods are synchronous; the caller is
 * expected to invoke them from a background thread.</p>
 *
 * <p>One instance owns one {@link SftpClient}; {@link #close()} closes it.</p>
 *
 * <p>Transfers accept an {@link AtomicBoolean} cancel flag. When set mid-flight
 * the copy loop throws {@link InterruptedIOException} and the partial file
 * (remote on upload / local on download) is best-effort deleted.</p>
 */
public final class SftpService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SftpService.class);

    private final SftpClient sftp;
    private volatile String cwd;

    public SftpService(SSHClient sshClient) throws IOException {
        ClientSession session = sshClient.getSession();
        if (session == null || !sshClient.isConnected()) {
            throw new IOException("SSH not connected");
        }
        this.sftp = SftpClientFactory.instance().createSftpClient(session);
        try {
            this.cwd = sftp.canonicalPath(".");
        } catch (IOException e) {
            this.cwd = "/";
        }
    }

    public String pwd() {
        return cwd;
    }

    public String canonical(String path) throws IOException {
        return sftp.canonicalPath(path);
    }

    /** List directory entries (excluding "." and ".."), sorted dirs-first then by name. */
    public List<RemoteEntry> list(String path) throws IOException {
        String resolved = sftp.canonicalPath(path);
        cwd = resolved;
        List<RemoteEntry> out = new ArrayList<>();
        for (SftpClient.DirEntry e : sftp.readDir(resolved)) {
            String n = e.getFilename();
            if (".".equals(n) || "..".equals(n)) continue;
            SftpClient.Attributes a = e.getAttributes();
            out.add(new RemoteEntry(
                    n,
                    a.getSize(),
                    a.isDirectory(),
                    a.getModifyTime() == null ? 0L : a.getModifyTime().toMillis()));
        }
        out.sort((x, y) -> {
            if (x.isDirectory() != y.isDirectory()) {
                return x.isDirectory() ? -1 : 1;
            }
            return x.getName().compareToIgnoreCase(y.getName());
        });
        return out;
    }

    /** Concatenate path segments using POSIX "/" semantics. */
    public static String join(String parent, String child) {
        if (child == null || child.isEmpty()) return parent;
        if ("..".equals(child)) {
            if ("/".equals(parent) || parent.isEmpty()) return "/";
            int slash = parent.lastIndexOf('/');
            return slash <= 0 ? "/" : parent.substring(0, slash);
        }
        if (parent == null || parent.isEmpty()) return "/" + child;
        return parent.endsWith("/") ? parent + child : parent + "/" + child;
    }

    /**
     * Delete a remote entry. Files are removed directly. Directories are
     * removed only when empty — non-empty directories throw an
     * {@link IOException} so the caller can warn the user (we don't
     * recursively delete on the server: too easy to wreck things).
     */
    public void delete(RemoteEntry entry, String parentPath) throws IOException {
        String path = join(parentPath, entry.getName());
        if (entry.isDirectory()) {
            sftp.rmdir(path);
        } else {
            sftp.remove(path);
        }
    }

    /** Upload a local file to a remote path (overwrites). Cancellable. */
    public void upload(Path local, String remotePath,
                       ProgressListener listener, AtomicBoolean cancel) throws IOException {
        long total = Files.size(local);
        boolean ok = false;
        try (InputStream in = Files.newInputStream(local);
             OutputStream out = sftp.write(remotePath,
                     SftpClient.OpenMode.Create,
                     SftpClient.OpenMode.Write,
                     SftpClient.OpenMode.Truncate)) {
            copy(in, out, total, listener, cancel);
            ok = true;
        } finally {
            if (!ok) {
                // Don't leave a partial file on the server.
                try {
                    sftp.remove(remotePath);
                } catch (Exception ignored) {
                }
            }
        }
    }

    /** Download a remote file to a local path (overwrites). Cancellable. */
    public void download(String remotePath, Path local,
                         ProgressListener listener, AtomicBoolean cancel) throws IOException {
        SftpClient.Attributes a = sftp.stat(remotePath);
        long total = a.getSize();
        boolean ok = false;
        try (InputStream in = sftp.read(remotePath);
             OutputStream out = Files.newOutputStream(local)) {
            copy(in, out, total, listener, cancel);
            ok = true;
        } finally {
            if (!ok) {
                try {
                    Files.deleteIfExists(local);
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static void copy(InputStream in, OutputStream out, long total,
                             ProgressListener cb, AtomicBoolean cancel) throws IOException {
        byte[] buf = new byte[64 * 1024];
        long done = 0;
        int n;
        while ((n = in.read(buf)) > 0) {
            if (cancel != null && cancel.get()) {
                throw new InterruptedIOException("Transfer cancelled");
            }
            out.write(buf, 0, n);
            done += n;
            if (cb != null) cb.onProgress(done, total);
        }
        out.flush();
    }

    @Override
    public void close() {
        try {
            sftp.close();
        } catch (Exception e) {
            log.debug("sftp close: {}", e.getMessage());
        }
    }

    @FunctionalInterface
    public interface ProgressListener {
        void onProgress(long done, long total);
    }
}
