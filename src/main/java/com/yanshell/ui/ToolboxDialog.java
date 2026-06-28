package com.yanshell.ui;

import javax.swing.*;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;

/**
 * Global toolbox window — hosts standalone utilities that don't need an
 * open SSH session. Layout is a left list of tools + a right card pane;
 * register more tools via {@link #register} from {@link #initTools()}.
 */
public final class ToolboxDialog extends JDialog {

    private final DefaultListModel<ToolEntry> listModel = new DefaultListModel<>();
    private final JList<ToolEntry> toolList = new JList<>(listModel);
    private final CardLayout cards = new CardLayout();
    private final JPanel cardHost = new JPanel(cards);

    public ToolboxDialog(JFrame owner) {
        super(owner, "工具箱", false);
        setSize(820, 480);
        setMinimumSize(new Dimension(640, 360));
        setLocationRelativeTo(owner);

        toolList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        toolList.setVisibleRowCount(-1);
        toolList.setFixedCellHeight(28);
        toolList.addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) return;
            ToolEntry t = toolList.getSelectedValue();
            if (t != null) cards.show(cardHost, t.id);
        });

        JScrollPane leftScroll = new JScrollPane(toolList);
        leftScroll.setPreferredSize(new Dimension(180, 0));
        leftScroll.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1,
                UIManager.getColor("Separator.foreground")));

        JPanel content = new JPanel(new BorderLayout());
        content.add(leftScroll, BorderLayout.WEST);
        content.add(cardHost,   BorderLayout.CENTER);
        setContentPane(content);

        initTools();
        if (!listModel.isEmpty()) toolList.setSelectedIndex(0);
    }

    /** Plug new tools in here. */
    private void initTools() {
        register("port-kill", "端口进程", new PortKillToolPanel());
        // Future: register("base64", "Base64", new Base64ToolPanel()); etc.
    }

    private void register(String id, String displayName, JComponent panel) {
        listModel.addElement(new ToolEntry(id, displayName));
        cardHost.add(panel, id);
    }

    private record ToolEntry(String id, String displayName) {
        @Override public String toString() { return displayName; }
    }
}
