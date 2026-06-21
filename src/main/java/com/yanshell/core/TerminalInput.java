package com.yanshell.core;

/**
 * Sink for keystrokes / bytes typed into the terminal.
 * Implemented by the SSH layer; the terminal layer only writes to it.
 */
@FunctionalInterface
public interface TerminalInput {
    void send(byte[] data);
}
