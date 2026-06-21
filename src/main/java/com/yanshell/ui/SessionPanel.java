package com.yanshell.ui;

import com.yanshell.core.ConnectionFolder;
import com.yanshell.core.ConnectionNode;
import com.yanshell.core.ConnectionProfile;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.Consumer;

/**
 * Left-hand panel showing the saved-connection tree.
 *
 * <p>The persisted model lives in {@link ConnectionFolder} (root); this
 * panel mirrors it into a Swing {@link DefaultTreeModel} where every
 * {@link DefaultMutableTreeNode#getUserObject()} is the matching
 * {@link ConnectionNode} (folder or profile).</p>
 *
 * <p>Edits go through the helper mutators ({@link #addProfile},
 * {@link #addFolder}, {@link #replaceSelected}, {@link #removeSelected})
 * which mutate both the underlying model and the Swing tree, then fire
 * {@link #setOnChanged}. The owner persists on each change.</p>
 */
public final class SessionPanel extends JPanel {

    private final ConnectionFolder root = new ConnectionFolder("");
    private final DefaultMutableTreeNode rootNode = new DefaultMutableTreeNode(root);
    private final DefaultTreeModel treeModel = new DefaultTreeModel(rootNode);
    private final JTree tree = new JTree(treeModel);

    private Consumer<ConnectionProfile> onOpen = p -> {};
    private Runnable onEdit    = () -> {};
    private Runnable onDelete  = () -> {};
    private Runnable onNewProfile = () -> {};
    private Runnable onAddFolder = () -> {};
    private Runnable onChanged = () -> {};
    private Runnable onCollapse = () -> {};

    public SessionPanel() {
        super(new BorderLayout());
        setBackground(UIManager.getColor("Panel.background"));

        // Header bar: title on the left, "+" dropdown on the right.
        // The dropdown is colocated with the data it creates — much clearer
        // than a top-level toolbar button labelled "文件夹".
        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(true);
        header.setBackground(UIManager.getColor("Panel.background"));
        header.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 6));

        JLabel title = new JLabel("连接");
        title.setFont(title.getFont().deriveFont(Font.BOLD));
        header.add(title, BorderLayout.WEST);

        JButton plus = new JButton("+");
        plus.setToolTipText("新增…");
        plus.setMargin(new java.awt.Insets(0, 6, 0, 6));
        plus.setFocusPainted(false);
        plus.putClientProperty("JButton.buttonType", "roundRect");
        plus.addActionListener(e -> {
            JPopupMenu m = new JPopupMenu();
            m.add(item("新建连接…", () -> onNewProfile.run()));
            m.add(item("新建文件夹…", () -> onAddFolder.run()));
            m.show(plus, 0, plus.getHeight());
        });

        JButton collapse = new JButton("⟨");
        collapse.setToolTipText("收起连接列表");
        collapse.setMargin(new java.awt.Insets(0, 6, 0, 6));
        collapse.setFocusPainted(false);
        collapse.putClientProperty("JButton.buttonType", "roundRect");
        collapse.addActionListener(e -> onCollapse.run());

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        actions.setOpaque(false);
        actions.add(plus);
        actions.add(collapse);
        header.add(actions, BorderLayout.EAST);

        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setRowHeight(26);
        tree.setBackground(UIManager.getColor("Tree.background"));
        tree.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        tree.setCellRenderer(new NodeRenderer());

        tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    ConnectionProfile p = getSelectedProfile();
                    if (p != null) onOpen.accept(p);
                }
            }
            @Override public void mousePressed (MouseEvent e) { maybePopup(e); }
            @Override public void mouseReleased(MouseEvent e) { maybePopup(e); }
        });

        JScrollPane scroll = new JScrollPane(tree);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setBackground(UIManager.getColor("Tree.background"));

        add(header, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);

        setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, UIManager.getColor("Separator.foreground")));
        setPreferredSize(new Dimension(240, 0));
    }

    // ---- callbacks -------------------------------------------------------

    public void setOnOpen(Consumer<ConnectionProfile> cb) { this.onOpen = cb; }
    public void setOnEdit(Runnable cb)                    { this.onEdit = cb; }
    public void setOnDelete(Runnable cb)                  { this.onDelete = cb; }
    public void setOnNewProfile(Runnable cb)              { this.onNewProfile = cb; }
    public void setOnAddFolder(Runnable cb)               { this.onAddFolder = cb; }
    /** Fired when the user clicks the collapse button in the header. */
    public void setOnCollapse(Runnable cb)                { this.onCollapse = cb; }
    /** Fired after the model changes (add / edit / delete). For persistence. */
    public void setOnChanged(Runnable cb)                 { this.onChanged = cb; }

    // ---- model exposure --------------------------------------------------

    /** Replace the entire tree (used at startup). */
    public void setRoot(ConnectionFolder loaded) {
        root.setName(loaded.getName());
        root.getChildren().clear();
        root.getChildren().addAll(loaded.getChildren());
        rebuildSwingTree();
    }

    /** Snapshot of the persisted tree root (live reference). */
    public ConnectionFolder getRoot() {
        return root;
    }

    // ---- selection helpers ----------------------------------------------

    public ConnectionNode getSelected() {
        DefaultMutableTreeNode n = selectedNode();
        return n == null ? null : (ConnectionNode) n.getUserObject();
    }

    public ConnectionProfile getSelectedProfile() {
        ConnectionNode n = getSelected();
        return n instanceof ConnectionProfile p ? p : null;
    }

    private DefaultMutableTreeNode selectedNode() {
        TreePath path = tree.getSelectionPath();
        return path == null ? null : (DefaultMutableTreeNode) path.getLastPathComponent();
    }

    /**
     * Pick the folder where a new entry should go:
     * - if a folder is selected, that folder
     * - if a profile is selected, its parent folder
     * - otherwise, the root
     */
    private DefaultMutableTreeNode targetFolderNode() {
        DefaultMutableTreeNode sel = selectedNode();
        if (sel == null) return rootNode;
        Object u = sel.getUserObject();
        if (u instanceof ConnectionFolder) return sel;
        DefaultMutableTreeNode parent = (DefaultMutableTreeNode) sel.getParent();
        return parent == null ? rootNode : parent;
    }

    private static ConnectionFolder folderOf(DefaultMutableTreeNode swingNode) {
        return (ConnectionFolder) swingNode.getUserObject();
    }

    // ---- mutators --------------------------------------------------------

    public void addProfile(ConnectionProfile profile) {
        DefaultMutableTreeNode parentSwing = targetFolderNode();
        folderOf(parentSwing).getChildren().add(profile);
        DefaultMutableTreeNode newNode = new DefaultMutableTreeNode(profile);
        treeModel.insertNodeInto(newNode, parentSwing, parentSwing.getChildCount());
        tree.expandPath(new TreePath(parentSwing.getPath()));
        tree.setSelectionPath(new TreePath(newNode.getPath()));
        onChanged.run();
    }

    public void addFolder(ConnectionFolder folder) {
        DefaultMutableTreeNode parentSwing = targetFolderNode();
        folderOf(parentSwing).getChildren().add(folder);
        DefaultMutableTreeNode newNode = new DefaultMutableTreeNode(folder);
        treeModel.insertNodeInto(newNode, parentSwing, parentSwing.getChildCount());
        tree.expandPath(new TreePath(parentSwing.getPath()));
        tree.setSelectionPath(new TreePath(newNode.getPath()));
        onChanged.run();
    }

    /** Replace the selected node's payload (used when editing). */
    public void replaceSelected(ConnectionNode replacement) {
        DefaultMutableTreeNode sel = selectedNode();
        if (sel == null || sel == rootNode) return;
        DefaultMutableTreeNode parentSwing = (DefaultMutableTreeNode) sel.getParent();
        ConnectionFolder parentModel = folderOf(parentSwing);
        int idx = parentSwing.getIndex(sel);
        if (idx < 0) return;
        parentModel.getChildren().set(idx, replacement);
        sel.setUserObject(replacement);
        treeModel.nodeChanged(sel);
        onChanged.run();
    }

    public void removeSelected() {
        DefaultMutableTreeNode sel = selectedNode();
        if (sel == null || sel == rootNode) return;
        DefaultMutableTreeNode parentSwing = (DefaultMutableTreeNode) sel.getParent();
        ConnectionFolder parentModel = folderOf(parentSwing);
        ConnectionNode payload = (ConnectionNode) sel.getUserObject();
        parentModel.getChildren().remove(payload);
        treeModel.removeNodeFromParent(sel);
        onChanged.run();
    }

    // ---- internals -------------------------------------------------------

    private void rebuildSwingTree() {
        rootNode.setUserObject(root);
        rootNode.removeAllChildren();
        appendChildren(rootNode, root);
        treeModel.reload(rootNode);
        // Auto-expand top level so the user sees something.
        for (int i = 0; i < rootNode.getChildCount(); i++) {
            DefaultMutableTreeNode c = (DefaultMutableTreeNode) rootNode.getChildAt(i);
            tree.expandPath(new TreePath(c.getPath()));
        }
    }

    private void appendChildren(DefaultMutableTreeNode swingParent, ConnectionFolder modelParent) {
        for (ConnectionNode child : modelParent.getChildren()) {
            DefaultMutableTreeNode swingChild = new DefaultMutableTreeNode(child);
            swingParent.add(swingChild);
            if (child instanceof ConnectionFolder f) {
                appendChildren(swingChild, f);
            }
        }
    }

    private void maybePopup(MouseEvent e) {
        if (!e.isPopupTrigger()) return;
        int row = tree.getClosestRowForLocation(e.getX(), e.getY());
        if (row >= 0) tree.setSelectionRow(row);

        ConnectionNode sel = getSelected();
        JPopupMenu menu = new JPopupMenu();
        if (sel instanceof ConnectionProfile) {
            menu.add(item("连接", () -> onOpen.accept((ConnectionProfile) sel)));
            menu.addSeparator();
        }
        menu.add(item("新建文件夹…", onAddFolder));
        if (sel != null) {
            menu.add(item("编辑…", onEdit));
            menu.add(item("删除", onDelete));
        }
        menu.show(tree, e.getX(), e.getY());
    }

    private static JMenuItem item(String label, Runnable r) {
        JMenuItem mi = new JMenuItem(label);
        mi.addActionListener(e -> SwingUtilities.invokeLater(r));
        return mi;
    }

    // ---- renderer --------------------------------------------------------

    /** Tree cell renderer that distinguishes folders from profiles. */
    private static final class NodeRenderer extends DefaultTreeCellRenderer {

        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value,
                                                      boolean sel, boolean expanded,
                                                      boolean leaf, int row,
                                                      boolean hasFocus) {
            super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus);
            Object u = (value instanceof DefaultMutableTreeNode n) ? n.getUserObject() : value;
            if (u instanceof ConnectionFolder f) {
                setText("📁  " + (f.getName().isEmpty() ? "(root)" : f.getName()));
            } else if (u instanceof ConnectionProfile p) {
                // host:port shown as a faint suffix.
                setText("<html><span>● </span>" + esc(p.getName())
                        + " <span style='color:#6A737D'>" + esc(p.describe()) + "</span></html>");
            }
            return this;
        }

        private static String esc(String s) {
            return s == null ? "" :
                    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        }
    }
}
