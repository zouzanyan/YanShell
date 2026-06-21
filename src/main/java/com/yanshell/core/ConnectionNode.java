package com.yanshell.core;

/**
 * Node in the saved-connection tree. Either a {@link ConnectionFolder}
 * (group, may contain children) or a {@link ConnectionProfile} (leaf,
 * holds the SSH configuration).
 *
 * <p>Sealed so the persistence layer and renderer can switch on the two
 * cases exhaustively.</p>
 */
public sealed interface ConnectionNode permits ConnectionFolder, ConnectionProfile {
    String getName();
    void setName(String name);
}
