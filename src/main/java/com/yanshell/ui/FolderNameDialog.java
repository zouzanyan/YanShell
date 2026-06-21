package com.yanshell.ui;

import javax.swing.JOptionPane;
import java.awt.Component;

/**
 * Tiny helper that prompts for a folder name. Returns the trimmed name,
 * or {@code null} if the user cancels or enters blank.
 */
public final class FolderNameDialog {

    private FolderNameDialog() {
    }

    public static String ask(Component parent, String initial) {
        String name = (String) JOptionPane.showInputDialog(parent,
                "文件夹名称:",
                "新建文件夹",
                JOptionPane.PLAIN_MESSAGE,
                null, null, initial == null ? "" : initial);
        if (name == null) return null;
        name = name.trim();
        return name.isEmpty() ? null : name;
    }
}
