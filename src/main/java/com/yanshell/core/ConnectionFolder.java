package com.yanshell.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Group node in the connection tree. Holds an ordered list of children
 * (folders or profiles). The root of the tree is also a folder (with an
 * empty / synthetic name).
 */
public final class ConnectionFolder implements ConnectionNode {

    private String name;
    private final List<ConnectionNode> children = new ArrayList<>();

    public ConnectionFolder(String name) {
        this.name = name;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public void setName(String name) {
        this.name = name;
    }

    public List<ConnectionNode> getChildren() {
        return children;
    }
}
