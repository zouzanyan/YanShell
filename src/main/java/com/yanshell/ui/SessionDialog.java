package com.yanshell.ui;

import com.yanshell.core.ConnectionProfile;
import com.yanshell.ssh.SSHConfig;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;

/**
 * Modal dialog for creating / editing a {@link ConnectionProfile}.
 * Returns the result via {@link #getResult()} or {@code null} on cancel.
 */
public final class SessionDialog extends JDialog {

    private final JTextField nameField = new JTextField(22);
    private final JTextField hostField = new JTextField(22);
    private final JTextField portField = new JTextField("22", 6);
    private final JTextField userField = new JTextField("", 22);
    private final JPasswordField passwordField = new JPasswordField(22);
    private final JTextField keyPathField = new JTextField(22);
    private final JPasswordField keyPassField = new JPasswordField(22);

    private ConnectionProfile result;

    public SessionDialog(Frame owner, ConnectionProfile existing) {
        super(owner, existing == null ? "新建连接" : "编辑连接", true);

        if (existing != null) {
            nameField.setText(existing.getName());
            SSHConfig cfg = existing.getConfig();
            hostField.setText(cfg.getHost());
            portField.setText(String.valueOf(cfg.getPort()));
            userField.setText(cfg.getUsername());
            if (cfg.getPassword() != null) passwordField.setText(cfg.getPassword());
            if (cfg.getPrivateKeyPath() != null) keyPathField.setText(cfg.getPrivateKeyPath());
            if (cfg.getKeyPassphrase() != null) keyPassField.setText(cfg.getKeyPassphrase());
        }

        // Header bar — thin colored strip + title, like FinalShell's session editor.
        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(UIManager.getColor("Panel.background"));
        header.setBorder(BorderFactory.createEmptyBorder(12, 16, 12, 16));
        JLabel title = new JLabel(existing == null ? "新建 SSH 连接" : "编辑 SSH 连接");
        title.setFont(title.getFont().deriveFont(Font.BOLD, title.getFont().getSize2D() + 2f));
        header.add(title, BorderLayout.WEST);

        // ---- Section: 基本信息 ----
        JPanel basic = sectionPanel("基本信息");
        addRow(basic, 0, "名称:", nameField);
        addRow(basic, 1, "主机:", hostField);
        addRow(basic, 2, "端口:", portField);
        addRow(basic, 3, "用户名:", userField);

        // ---- Section: 认证 ----
        JPanel auth = sectionPanel("认证");
        passwordField.putClientProperty("JTextField.showRevealButton", true);
        keyPassField.putClientProperty("JTextField.showRevealButton", true);
        keyPathField.putClientProperty("JTextField.placeholderText", "可选 — 私钥文件路径");

        addRow(auth, 0, "密码:", passwordField);

        JPanel keyRow = new JPanel(new BorderLayout(6, 0));
        keyRow.add(keyPathField, BorderLayout.CENTER);
        JButton browse = new JButton("浏览…");
        browse.addActionListener(e -> {
            JFileChooser fc = new JFileChooser();
            fc.setDialogTitle("选择私钥文件");
            if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                keyPathField.setText(fc.getSelectedFile().getAbsolutePath());
            }
        });
        keyRow.add(browse, BorderLayout.EAST);
        addRow(auth, 1, "私钥:", keyRow);

        addRow(auth, 2, "私钥密码:", keyPassField);

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBorder(BorderFactory.createEmptyBorder(8, 16, 8, 16));
        content.add(basic);
        content.add(Box.createVerticalStrut(6));
        content.add(auth);

        // ---- Footer: buttons ----
        JButton ok = new JButton(existing == null ? "确定" : "保存");
        JButton cancel = new JButton("取消");
        ok.putClientProperty("JButton.buttonType", "default");
        getRootPane().setDefaultButton(ok);
        ok.addActionListener(e -> onOk());
        cancel.addActionListener(e -> {
            result = null;
            dispose();
        });

        JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
        footer.setBorder(BorderFactory.createEmptyBorder(0, 12, 8, 12));
        footer.add(cancel);
        footer.add(ok);

        setLayout(new BorderLayout());
        add(header, BorderLayout.NORTH);
        add(content, BorderLayout.CENTER);
        add(footer, BorderLayout.SOUTH);

        pack();
        Dimension size = getSize();
        setSize(Math.max(size.width, 480), size.height);
        setLocationRelativeTo(owner);
    }

    private JPanel sectionPanel(String titleText) {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder(
                        BorderFactory.createMatteBorder(1, 0, 0, 0, UIManager.getColor("Separator.foreground")),
                        " " + titleText + " "),
                BorderFactory.createEmptyBorder(4, 6, 8, 6)));
        return p;
    }

    private void addRow(JPanel panel, int row, String label, JComponent field) {
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(5, 4, 5, 4);
        c.fill = GridBagConstraints.HORIZONTAL;
        c.anchor = GridBagConstraints.WEST;

        c.gridx = 0;
        c.gridy = row;
        c.weightx = 0;
        JLabel l = new JLabel(label);
        l.setPreferredSize(new Dimension(80, l.getPreferredSize().height));
        panel.add(l, c);

        c.gridx = 1;
        c.gridy = row;
        c.weightx = 1;
        panel.add(field, c);
    }

    private void onOk() {
        String name = nameField.getText().trim();
        String host = hostField.getText().trim();
        String user = userField.getText().trim();
        if (host.isEmpty() || user.isEmpty()) {
            warn("主机和用户名不能为空");
            return;
        }
        if (name.isEmpty()) {
            name = user + "@" + host;
        }

        int port;
        try {
            port = Integer.parseInt(portField.getText().trim());
            if (port <= 0 || port > 65535) throw new NumberFormatException();
        } catch (NumberFormatException ex) {
            warn("端口必须是 1-65535 的数字");
            return;
        }

        String password = new String(passwordField.getPassword());
        String keyPath = keyPathField.getText().trim();
        String keyPass = new String(keyPassField.getPassword());

        if (password.isEmpty() && keyPath.isEmpty()) {
            warn("请提供密码或私钥");
            return;
        }

        SSHConfig cfg = new SSHConfig(host, port, user, password, keyPath, keyPass);
        result = new ConnectionProfile(name, cfg);
        dispose();
    }

    private void warn(String msg) {
        JOptionPane.showMessageDialog(this, msg, "提示", JOptionPane.WARNING_MESSAGE);
    }

    public ConnectionProfile getResult() {
        return result;
    }
}
