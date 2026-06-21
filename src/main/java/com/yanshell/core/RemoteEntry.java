package com.yanshell.core;

/**
 * Listing entry for a remote SFTP directory.
 * Plain holder; produced by {@code SftpService} and consumed by the UI.
 */
public final class RemoteEntry {

    private final String name;
    private final long size;
    private final boolean directory;
    private final long mtimeMillis;

    public RemoteEntry(String name, long size, boolean directory, long mtimeMillis) {
        this.name = name;
        this.size = size;
        this.directory = directory;
        this.mtimeMillis = mtimeMillis;
    }

    public String getName()         { return name; }
    public long getSize()           { return size; }
    public boolean isDirectory()    { return directory; }
    public long getMtimeMillis()    { return mtimeMillis; }
}
