package com.yanshell.ui;

import com.yanshell.core.RemoteEntry;
import com.yanshell.sftp.SftpService;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListCellRenderer;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.datatransfer.DataFlavor;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DropTarget;
import java.awt.dnd.DropTargetAdapter;
import java.awt.dnd.DropTargetDropEvent;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Two-pane file manager — local on the left, remote (SFTP) on the right.
 *
 * <p>Operations:</p>
 * <ul>
 *   <li>Navigate (double-click), refresh, type a path</li>
 *   <li>Upload / download via toolbar buttons or drag-and-drop from the OS</li>
 *   <li>Delete via right-click menu or {@code Delete} key</li>
 *   <li>Cancel an active transfer with the bottom-bar "取消" button</li>
 * </ul>
 *
 * <p>Directory deletion is allowed but only for empty directories — non-empty
 * dirs throw an error from the SFTP layer; we don't recursively delete on
 * the server because it's too easy to wreck things.</p>
 */
public final class FileManagerPanel extends JPanel {

    private SftpService sftp;

    // Local side.
    private final JTextField localPathField = new JTextField();
    private final DefaultListModel<File> localModel = new DefaultListModel<>();
    private final JList<File> localList = new JList<>(localModel);
    private File localCwd;

    // Remote side.
    private final JTextField remotePathField = new JTextField();
    private final DefaultListModel<RemoteEntry> remoteModel = new DefaultListModel<>();
    private final JList<RemoteEntry> remoteList = new JList<>(remoteModel);
    private String remoteCwd;

    // Bottom transfer status.
    private final JProgressBar progress = new JProgressBar(0, 100);
    private final JLabel progressLabel = new JLabel(" ");
    private final JButton cancelButton = new JButton("取消");

    /**
     * Cancellation flag for the active transfer. Replaced before each new
     * transfer; the running thread reads it inside the copy loop.
     */
    private volatile AtomicBoolean activeCancel;

    public FileManagerPanel() {
        super(new BorderLayout());

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                buildLocalPane(), buildRemotePane());
        split.setBorder(null);
        split.setResizeWeight(0.5);
        split.setDividerSize(6);
        // setDividerLocation(0.5) is ignored while the split has zero width
        // (constructor time). Apply the 50/50 split once it is first sized,
        // then stop — resizeWeight keeps it balanced on later resizes.
        split.addComponentListener(new java.awt.event.ComponentAdapter() {
            private boolean done;
            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                if (!done && split.getWidth() > 0) {
                    done = true;
                    split.setDividerLocation(0.5);
                }
            }
        });

        add(split, BorderLayout.CENTER);
        add(buildBottom(), BorderLayout.SOUTH);

        navigateLocal(new File(System.getProperty("user.home", ".")));
    }

    public void attach(SftpService sftp) {
        this.sftp = sftp;
        try {
            navigateRemote(sftp.pwd());
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this,
                    "无法读取远程目录: " + ex.getMessage(),
                    "SFTP", JOptionPane.ERROR_MESSAGE);
        }
    }

    public void detach() {
        if (sftp != null) {
            sftp.close();
            sftp = null;
        }
        AtomicBoolean c = activeCancel;
        if (c != null) c.set(true);
        remoteModel.clear();
        remotePathField.setText("");
        remoteCwd = null;
    }

    // ---- panes -----------------------------------------------------------

    private JPanel buildLocalPane() {
        JLabel title = headerLabel("本机");

        JButton up = new JButton("↑");
        up.setToolTipText("上一级目录");
        up.addActionListener(e -> {
            if (localCwd != null && localCwd.getParentFile() != null) {
                navigateLocal(localCwd.getParentFile());
            }
        });
        localPathField.addActionListener(e -> {
            File f = new File(localPathField.getText().trim());
            if (f.isDirectory()) {
                navigateLocal(f);
            } else {
                localPathField.setText(localCwd == null ? "" : localCwd.getAbsolutePath());
            }
        });

        JPanel topRow = new JPanel(new BorderLayout(4, 0));
        topRow.setOpaque(false);
        topRow.add(up, BorderLayout.WEST);
        topRow.add(localPathField, BorderLayout.CENTER);

        JPanel header = new JPanel(new BorderLayout(0, 4));
        header.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        header.add(title, BorderLayout.NORTH);
        header.add(topRow, BorderLayout.CENTER);

        localList.setCellRenderer(new LocalRenderer());
        localList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    File f = localList.getSelectedValue();
                    if (f != null && f.isDirectory()) navigateLocal(f);
                }
            }
            @Override public void mousePressed (MouseEvent e) { maybeLocalPopup(e); }
            @Override public void mouseReleased(MouseEvent e) { maybeLocalPopup(e); }
        });
        bindDeleteKey(localList, this::doDeleteLocal);

        JPanel pane = new JPanel(new BorderLayout());
        pane.add(header, BorderLayout.NORTH);
        pane.add(new JScrollPane(localList), BorderLayout.CENTER);
        return pane;
    }

    private JPanel buildRemotePane() {
        JLabel title = headerLabel("远程");
        title.setToolTipText("可将文件从资源管理器拖到此处上传");

        JButton up = new JButton("↑");
        up.setToolTipText("上一级目录");
        up.addActionListener(e -> {
            if (sftp == null || remoteCwd == null) return;
            navigateRemote(SftpService.join(remoteCwd, ".."));
        });
        JButton refresh = new JButton("⟳");
        refresh.setToolTipText("刷新");
        refresh.addActionListener(e -> {
            if (sftp != null && remoteCwd != null) navigateRemote(remoteCwd);
        });
        remotePathField.addActionListener(e -> {
            if (sftp == null) return;
            String p = remotePathField.getText().trim();
            if (!p.isEmpty()) navigateRemote(p);
        });

        JButton upload = new JButton("⬅ 上传");
        JButton download = new JButton("下载 ➡");
        upload.setToolTipText("上传本地选中文件到远程当前目录");
        download.setToolTipText("下载远程选中文件到本地当前目录");
        upload.addActionListener(e -> doUpload());
        download.addActionListener(e -> doDownload());

        JPanel buttonRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        buttonRow.setOpaque(false);
        buttonRow.add(upload);
        buttonRow.add(download);

        JPanel topRow = new JPanel(new BorderLayout(4, 0));
        topRow.setOpaque(false);
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        left.setOpaque(false);
        left.add(up);
        left.add(refresh);
        topRow.add(left, BorderLayout.WEST);
        topRow.add(remotePathField, BorderLayout.CENTER);

        JPanel header = new JPanel(new BorderLayout(0, 4));
        header.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        JPanel headerTop = new JPanel(new BorderLayout());
        headerTop.setOpaque(false);
        headerTop.add(title, BorderLayout.WEST);
        headerTop.add(buttonRow, BorderLayout.EAST);
        header.add(headerTop, BorderLayout.NORTH);
        header.add(topRow, BorderLayout.CENTER);

        remoteList.setCellRenderer(new RemoteRenderer());
        remoteList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    RemoteEntry r = remoteList.getSelectedValue();
                    if (r != null && r.isDirectory() && remoteCwd != null) {
                        navigateRemote(SftpService.join(remoteCwd, r.getName()));
                    }
                }
            }
            @Override public void mousePressed (MouseEvent e) { maybeRemotePopup(e); }
            @Override public void mouseReleased(MouseEvent e) { maybeRemotePopup(e); }
        });
        bindDeleteKey(remoteList, this::doDeleteRemote);

        JPanel pane = new JPanel(new BorderLayout());
        pane.add(header, BorderLayout.NORTH);
        pane.add(new JScrollPane(remoteList), BorderLayout.CENTER);

        // DnD: drop OS files onto the pane to upload. Both the pane and the
        // inner list need a target — JList consumes mouse events so the
        // parent's DropTarget alone wouldn't fire.
        DropTargetAdapter dnd = new DropTargetAdapter() {
            @Override
            public void drop(DropTargetDropEvent dtde) {
                handleFileDrop(dtde);
            }
        };
        new DropTarget(pane, DnDConstants.ACTION_COPY, dnd, true);
        new DropTarget(remoteList, DnDConstants.ACTION_COPY, dnd, true);
        return pane;
    }

    private JPanel buildBottom() {
        JPanel p = new JPanel(new BorderLayout(8, 0));
        p.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, UIManager.getColor("Separator.foreground")),
                BorderFactory.createEmptyBorder(4, 10, 4, 10)));
        progress.setStringPainted(true);
        progress.setPreferredSize(new Dimension(200, 16));
        progressLabel.setForeground(UIManager.getColor("Label.disabledForeground"));

        cancelButton.setEnabled(false);
        cancelButton.setMargin(new java.awt.Insets(0, 8, 0, 8));
        cancelButton.setToolTipText("取消当前传输");
        cancelButton.addActionListener(e -> {
            AtomicBoolean c = activeCancel;
            if (c != null) c.set(true);
            cancelButton.setEnabled(false);
            progressLabel.setText("正在取消…");
        });

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        right.setOpaque(false);
        right.add(progress);
        right.add(cancelButton);

        p.add(progressLabel, BorderLayout.CENTER);
        p.add(right, BorderLayout.EAST);
        return p;
    }

    private static JLabel headerLabel(String text) {
        JLabel l = new JLabel(text);
        l.setFont(l.getFont().deriveFont(Font.BOLD));
        return l;
    }

    // ---- popup menus + Delete key ---------------------------------------

    private void maybeLocalPopup(MouseEvent e) {
        if (!e.isPopupTrigger()) return;
        int idx = localList.locationToIndex(e.getPoint());
        if (idx >= 0) localList.setSelectedIndex(idx);
        File f = localList.getSelectedValue();
        if (f == null) return;

        JPopupMenu m = new JPopupMenu();
        m.add(menuItem("打开", () -> {
            if (f.isDirectory()) navigateLocal(f);
        }));
        m.add(menuItem("上传到远程", () -> {
            if (f.isFile()) uploadFiles(List.of(f));
        }));
        m.addSeparator();
        m.add(menuItem("删除", this::doDeleteLocal));
        m.show(localList, e.getX(), e.getY());
    }

    private void maybeRemotePopup(MouseEvent e) {
        if (!e.isPopupTrigger()) return;
        int idx = remoteList.locationToIndex(e.getPoint());
        if (idx >= 0) remoteList.setSelectedIndex(idx);
        RemoteEntry r = remoteList.getSelectedValue();
        if (r == null) return;

        JPopupMenu m = new JPopupMenu();
        m.add(menuItem("打开", () -> {
            if (r.isDirectory() && remoteCwd != null) {
                navigateRemote(SftpService.join(remoteCwd, r.getName()));
            }
        }));
        if (!r.isDirectory()) {
            m.add(menuItem("下载到本地", this::doDownload));
        }
        m.addSeparator();
        m.add(menuItem("刷新", () -> {
            if (sftp != null && remoteCwd != null) navigateRemote(remoteCwd);
        }));
        m.add(menuItem("删除", this::doDeleteRemote));
        m.show(remoteList, e.getX(), e.getY());
    }

    private static JMenuItem menuItem(String label, Runnable r) {
        JMenuItem mi = new JMenuItem(label);
        mi.addActionListener(e -> r.run());
        return mi;
    }

    /** Bind {@code Delete} on the list to the supplied handler. */
    private static void bindDeleteKey(JList<?> list, Runnable handler) {
        list.getInputMap(JComponent.WHEN_FOCUSED)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "yanshell-delete");
        list.getActionMap().put("yanshell-delete", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                handler.run();
            }
        });
    }

    // ---- delete ---------------------------------------------------------

    private void doDeleteLocal() {
        File f = localList.getSelectedValue();
        if (f == null) return;
        String kind = f.isDirectory() ? "目录" : "文件";
        int r = JOptionPane.showConfirmDialog(this,
                "删除本地" + kind + " “" + f.getName() + "” ？",
                "确认删除", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE);
        if (r != JOptionPane.OK_OPTION) return;

        try {
            if (f.isDirectory()) {
                // Files.delete refuses non-empty directories, which is what
                // we want — clear errors instead of silent recursive rm.
                Files.delete(f.toPath());
            } else {
                Files.delete(f.toPath());
            }
            navigateLocal(localCwd);
        } catch (java.nio.file.DirectoryNotEmptyException ex) {
            JOptionPane.showMessageDialog(this,
                    "目录非空，请先清空内容再删除。",
                    "无法删除", JOptionPane.WARNING_MESSAGE);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this,
                    "删除失败: " + ex.getMessage(),
                    "无法删除", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void doDeleteRemote() {
        if (sftp == null || remoteCwd == null) return;
        RemoteEntry r = remoteList.getSelectedValue();
        if (r == null) return;
        String kind = r.isDirectory() ? "目录" : "文件";
        int ans = JOptionPane.showConfirmDialog(this,
                "删除远程" + kind + " “" + r.getName() + "” ？",
                "确认删除", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE);
        if (ans != JOptionPane.OK_OPTION) return;

        final SftpService svc = sftp;
        final String parent = remoteCwd;
        new Thread(() -> {
            try {
                svc.delete(r, parent);
                SwingUtilities.invokeLater(() -> navigateRemote(parent));
            } catch (Exception ex) {
                String msg = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase();
                final boolean nonEmpty = r.isDirectory() && (msg.contains("not empty")
                        || msg.contains("dir not empty") || msg.contains("failure"));
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this,
                        nonEmpty ? "目录非空，请先清空内容再删除。" : ("删除失败: " + ex.getMessage()),
                        "无法删除",
                        nonEmpty ? JOptionPane.WARNING_MESSAGE : JOptionPane.ERROR_MESSAGE));
            }
        }, "sftp-delete").start();
    }

    // ---- navigation ------------------------------------------------------

    private void navigateLocal(File dir) {
        File[] children = dir.listFiles();
        if (children == null) {
            JOptionPane.showMessageDialog(this,
                    "无法访问目录: " + dir, "本机", JOptionPane.WARNING_MESSAGE);
            return;
        }
        Arrays.sort(children, Comparator
                .comparing((File f) -> !f.isDirectory())
                .thenComparing(File::getName, String.CASE_INSENSITIVE_ORDER));
        localCwd = dir;
        localPathField.setText(dir.getAbsolutePath());
        localModel.clear();
        for (File f : children) localModel.addElement(f);
    }

    private void navigateRemote(String path) {
        if (sftp == null) return;
        new Thread(() -> {
            try {
                List<RemoteEntry> entries = sftp.list(path);
                String resolved = sftp.pwd();
                SwingUtilities.invokeLater(() -> {
                    remoteCwd = resolved;
                    remotePathField.setText(resolved);
                    remoteModel.clear();
                    for (RemoteEntry e : entries) remoteModel.addElement(e);
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this,
                        "读取远程目录失败: " + ex.getMessage(),
                        "SFTP", JOptionPane.ERROR_MESSAGE));
            }
        }, "sftp-list").start();
    }

    // ---- transfer --------------------------------------------------------

    private void doUpload() {
        if (sftp == null || remoteCwd == null) {
            JOptionPane.showMessageDialog(this, "未连接 SFTP", "提示", JOptionPane.WARNING_MESSAGE);
            return;
        }
        File local = localList.getSelectedValue();
        if (local == null) {
            JFileChooser fc = new JFileChooser(localCwd);
            if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
            local = fc.getSelectedFile();
        }
        if (local == null || !local.isFile()) {
            JOptionPane.showMessageDialog(this, "请选择一个文件（暂不支持目录）",
                    "上传", JOptionPane.WARNING_MESSAGE);
            return;
        }
        uploadFiles(List.of(local));
    }

    private void doDownload() {
        if (sftp == null) {
            JOptionPane.showMessageDialog(this, "未连接 SFTP", "提示", JOptionPane.WARNING_MESSAGE);
            return;
        }
        RemoteEntry r = remoteList.getSelectedValue();
        if (r == null || r.isDirectory()) {
            JOptionPane.showMessageDialog(this, "请在远程列表选择一个文件（暂不支持目录）",
                    "下载", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (localCwd == null) localCwd = new File(System.getProperty("user.home", "."));
        Path dest = Paths.get(localCwd.getAbsolutePath(), r.getName());
        if (Files.exists(dest)) {
            int ans = JOptionPane.showConfirmDialog(this,
                    "本地已存在 " + dest.getFileName() + "，覆盖？",
                    "下载", JOptionPane.OK_CANCEL_OPTION);
            if (ans != JOptionPane.OK_OPTION) return;
        }
        final String src = SftpService.join(remoteCwd, r.getName());

        AtomicBoolean cancel = beginTransfer("下载 " + r.getName());
        new Thread(() -> {
            String label = "下载 " + r.getName();
            try {
                sftp.download(src, dest, this::publishProgress, cancel);
                endTransfer(label, true, false, () -> navigateLocal(localCwd));
            } catch (InterruptedIOException ie) {
                endTransfer(label, false, true, null);
            } catch (Exception ex) {
                endTransferError(label, ex);
            }
        }, "sftp-download").start();
    }

    private void uploadFiles(List<File> files) {
        if (sftp == null || remoteCwd == null) {
            JOptionPane.showMessageDialog(this, "未连接 SFTP", "提示", JOptionPane.WARNING_MESSAGE);
            return;
        }
        List<File> regular = files.stream().filter(File::isFile).toList();
        if (regular.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "暂不支持上传目录。请选择文件。",
                    "上传", JOptionPane.WARNING_MESSAGE);
            return;
        }

        AtomicBoolean cancel = beginTransfer("上传 " + regular.get(0).getName()
                + (regular.size() > 1 ? " 等 " + regular.size() + " 个文件" : ""));
        final String destDir = remoteCwd;

        new Thread(() -> {
            int total = regular.size();
            int done = 0;
            try {
                for (File f : regular) {
                    if (cancel.get()) throw new InterruptedIOException("Transfer cancelled");
                    final int idx = ++done;
                    SwingUtilities.invokeLater(() ->
                            progressLabel.setText("上传 (" + idx + "/" + total + ") " + f.getName()));
                    String dest = SftpService.join(destDir, f.getName());
                    sftp.upload(f.toPath(), dest, this::publishProgress, cancel);
                }
                endTransfer("上传 " + total + " 个文件", true, false,
                        () -> navigateRemote(destDir));
            } catch (InterruptedIOException ie) {
                endTransfer("上传", false, true, () -> navigateRemote(destDir));
            } catch (Exception ex) {
                endTransferError("上传", ex);
            }
        }, "sftp-upload").start();
    }

    private AtomicBoolean beginTransfer(String label) {
        AtomicBoolean cancel = new AtomicBoolean(false);
        activeCancel = cancel;
        SwingUtilities.invokeLater(() -> {
            progressLabel.setText(label);
            progress.setValue(0);
            cancelButton.setEnabled(true);
        });
        return cancel;
    }

    private void endTransfer(String label, boolean ok, boolean cancelled, Runnable onDone) {
        SwingUtilities.invokeLater(() -> {
            activeCancel = null;
            cancelButton.setEnabled(false);
            if (ok) {
                progressLabel.setText(label + " — 完成");
                progress.setValue(100);
            } else if (cancelled) {
                progressLabel.setText(label + " — 已取消");
                progress.setValue(0);
            }
            if (onDone != null) onDone.run();
        });
    }

    private void endTransferError(String label, Exception ex) {
        SwingUtilities.invokeLater(() -> {
            activeCancel = null;
            cancelButton.setEnabled(false);
            progressLabel.setText(label + " — 失败");
            progress.setValue(0);
            JOptionPane.showMessageDialog(this,
                    ex.getMessage(),
                    "传输失败", JOptionPane.ERROR_MESSAGE);
        });
    }

    private void publishProgress(long done, long total) {
        int pct = (total <= 0) ? 0 : (int) Math.min(100, done * 100L / total);
        SwingUtilities.invokeLater(() -> progress.setValue(pct));
    }

    // ---- drag and drop ---------------------------------------------------

    @SuppressWarnings("unchecked")
    private void handleFileDrop(DropTargetDropEvent dtde) {
        if (sftp == null || remoteCwd == null) {
            dtde.rejectDrop();
            JOptionPane.showMessageDialog(this, "未连接 SFTP",
                    "提示", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (!dtde.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
            dtde.rejectDrop();
            return;
        }
        dtde.acceptDrop(DnDConstants.ACTION_COPY);
        try {
            List<File> files = (List<File>)
                    dtde.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
            dtde.dropComplete(true);
            if (files != null && !files.isEmpty()) {
                uploadFiles(files);
            }
        } catch (Exception ex) {
            dtde.dropComplete(false);
            JOptionPane.showMessageDialog(this,
                    "拖入失败: " + ex.getMessage(),
                    "拖拽上传", JOptionPane.ERROR_MESSAGE);
        }
    }

    // ---- renderers -------------------------------------------------------

    private static String formatSize(long size) {
        if (size < 1024) return size + " B";
        if (size < 1024L * 1024) return String.format("%.1f KB", size / 1024.0);
        if (size < 1024L * 1024 * 1024) return String.format("%.1f MB", size / 1048576.0);
        return String.format("%.2f GB", size / 1073741824.0);
    }

    private static final class LocalRenderer extends FileRowRenderer
            implements ListCellRenderer<File> {
        @Override
        public Component getListCellRendererComponent(JList<? extends File> list,
                                                      File value,
                                                      int index,
                                                      boolean isSelected,
                                                      boolean cellHasFocus) {
            String icon = value.isDirectory() ? "📁" : "📄";
            String sub = value.isDirectory() ? "目录" : formatSize(value.length());
            apply(icon, value.getName(), sub, list, isSelected);
            return this;
        }
    }

    private static final class RemoteRenderer extends FileRowRenderer
            implements ListCellRenderer<RemoteEntry> {
        @Override
        public Component getListCellRendererComponent(JList<? extends RemoteEntry> list,
                                                      RemoteEntry value,
                                                      int index,
                                                      boolean isSelected,
                                                      boolean cellHasFocus) {
            String icon = value.isDirectory() ? "📁" : "📄";
            String sub = value.isDirectory() ? "目录" : formatSize(value.getSize());
            apply(icon, value.getName(), sub, list, isSelected);
            return this;
        }
    }

    /** Shared row layout for the two list renderers. */
    private static class FileRowRenderer extends JPanel {
        private final JLabel iconLabel = new JLabel();
        private final JLabel nameLabel = new JLabel();
        private final JLabel subLabel = new JLabel();

        FileRowRenderer() {
            super(new BorderLayout(8, 0));
            setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
            iconLabel.setPreferredSize(new Dimension(20, 20));
            JPanel text = new JPanel(new BorderLayout());
            text.setOpaque(false);
            text.add(nameLabel, BorderLayout.CENTER);
            subLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
            subLabel.setFont(subLabel.getFont().deriveFont(subLabel.getFont().getSize2D() - 1f));
            add(iconLabel, BorderLayout.WEST);
            add(text, BorderLayout.CENTER);
            add(subLabel, BorderLayout.EAST);
        }

        void apply(String icon, String name, String sub, JList<?> list, boolean selected) {
            iconLabel.setText(icon);
            nameLabel.setText(name);
            subLabel.setText(sub);
            if (selected) {
                setBackground(UIManager.getColor("List.selectionBackground"));
                setOpaque(true);
            } else {
                setBackground(list.getBackground());
                setOpaque(false);
            }
        }
    }
}
