package com.yanshell.ui;

import com.yanshell.tools.LocalProcessUtil;
import com.yanshell.tools.LocalProcessUtil.PortProcess;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.List;

/**
 * Toolbox panel: look up which local process is occupying a TCP/UDP port
 * and kill it. Pure local OS work — runs without any open SSH session.
 *
 * <p>UI: top row is the port input + 查询 button; center is a result table;
 * bottom row is the kill action. All OS calls are run off the EDT via
 * {@link SwingWorker}.</p>
 */
public final class PortKillToolPanel extends JPanel {

    private static final String[] COLUMNS = {"PID", "进程名", "协议", "本地地址", "状态"};

    private final JTextField portField = new JTextField(8);
    private final JButton queryBtn = new JButton("查询");
    private final JButton killBtn = new JButton("结束进程");
    private final JCheckBox forceBox = new JCheckBox("强制 (-9 / /F)", true);
    private final JLabel statusLabel = new JLabel(" ");

    private final DefaultTableModel model = new DefaultTableModel(COLUMNS, 0) {
        @Override public boolean isCellEditable(int row, int column) { return false; }
        @Override public Class<?> getColumnClass(int col) {
            return col == 0 ? Integer.class : String.class;
        }
    };
    private final JTable table = new JTable(model);

    public PortKillToolPanel() {
        super(new BorderLayout(0, 8));
        setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        add(buildTop(),    BorderLayout.NORTH);
        add(buildCenter(), BorderLayout.CENTER);
        add(buildBottom(), BorderLayout.SOUTH);

        queryBtn.addActionListener(e -> runQuery());
        killBtn.addActionListener(e -> runKill());
        // Enter inside the port field triggers the query.
        portField.addActionListener(e -> runQuery());

        killBtn.setEnabled(false);
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                killBtn.setEnabled(table.getSelectedRow() >= 0);
            }
        });
    }

    private JComponent buildTop() {
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        top.add(new JLabel("端口："));
        portField.setToolTipText("输入要查询的本地端口号 (1-65535)，回车查询");
        top.add(portField);
        top.add(queryBtn);
        top.add(Box.createHorizontalStrut(12));
        top.add(new JLabel("提示：双击表格行也可定位 PID"));
        return top;
    }

    private JComponent buildCenter() {
        table.setRowHeight(22);
        table.setFillsViewportHeight(true);
        table.getColumnModel().getColumn(0).setPreferredWidth(70);
        table.getColumnModel().getColumn(1).setPreferredWidth(180);
        table.getColumnModel().getColumn(2).setPreferredWidth(60);
        table.getColumnModel().getColumn(3).setPreferredWidth(220);
        table.getColumnModel().getColumn(4).setPreferredWidth(100);

        JScrollPane sp = new JScrollPane(table);
        sp.setPreferredSize(new Dimension(640, 260));
        return sp;
    }

    private JComponent buildBottom() {
        JPanel bottom = new JPanel(new BorderLayout());
        statusLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
        bottom.add(statusLabel, BorderLayout.WEST);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        right.add(forceBox);
        killBtn.putClientProperty("JButton.buttonType", "roundRect");
        right.add(killBtn);
        bottom.add(right, BorderLayout.EAST);
        return bottom;
    }

    // ---- actions --------------------------------------------------------

    private Integer parsePort() {
        String text = portField.getText().trim();
        if (text.isEmpty()) {
            setStatus("请输入端口号");
            return null;
        }
        try {
            int p = Integer.parseInt(text);
            if (p < 1 || p > 65535) throw new NumberFormatException();
            return p;
        } catch (NumberFormatException ex) {
            setStatus("端口号无效：" + text);
            return null;
        }
    }

    private void runQuery() {
        Integer port = parsePort();
        if (port == null) return;
        model.setRowCount(0);
        setStatus("正在查询端口 " + port + " ...");
        queryBtn.setEnabled(false);
        killBtn.setEnabled(false);

        new SwingWorker<List<PortProcess>, Void>() {
            @Override protected List<PortProcess> doInBackground() {
                return LocalProcessUtil.findByPort(port);
            }
            @Override protected void done() {
                queryBtn.setEnabled(true);
                try {
                    List<PortProcess> rows = get();
                    for (PortProcess r : rows) {
                        model.addRow(new Object[]{
                                r.pid(), r.name(), r.protocol(), r.localAddress(), r.state()
                        });
                    }
                    if (rows.isEmpty()) {
                        setStatus("端口 " + port + " 未被占用");
                    } else {
                        setStatus("找到 " + rows.size() + " 条记录");
                    }
                } catch (Exception ex) {
                    setStatus("查询失败：" + ex.getMessage());
                }
            }
        }.execute();
    }

    private void runKill() {
        int row = table.getSelectedRow();
        if (row < 0) return;
        int pid = (Integer) model.getValueAt(row, 0);
        String name = String.valueOf(model.getValueAt(row, 1));

        int r = JOptionPane.showConfirmDialog(
                SwingUtilities.getWindowAncestor(this),
                "确定要结束进程 \"" + name + "\" (PID " + pid + ") 吗？",
                "结束进程",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE);
        if (r != JOptionPane.OK_OPTION) return;

        boolean force = forceBox.isSelected();
        setStatus("正在结束 PID " + pid + " ...");
        killBtn.setEnabled(false);

        new SwingWorker<Boolean, Void>() {
            @Override protected Boolean doInBackground() {
                return LocalProcessUtil.kill(pid, force);
            }
            @Override protected void done() {
                try {
                    boolean ok = get();
                    if (ok) {
                        setStatus("已结束 PID " + pid);
                        // Refresh so the dead entry disappears.
                        runQuery();
                    } else {
                        setStatus("结束 PID " + pid + " 失败（可能权限不足或进程已退出）");
                        killBtn.setEnabled(table.getSelectedRow() >= 0);
                    }
                } catch (Exception ex) {
                    setStatus("结束失败：" + ex.getMessage());
                    killBtn.setEnabled(table.getSelectedRow() >= 0);
                }
            }
        }.execute();
    }

    private void setStatus(String text) {
        statusLabel.setText(text);
    }
}
