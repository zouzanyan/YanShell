package com.yanshell.core;

/**
 * Sink for bytes coming back from the remote shell.
 * Implemented by the terminal layer; the SSH layer only writes to it.
 */
@FunctionalInterface
public interface TerminalOutput {
    void write(byte[] data);
}
