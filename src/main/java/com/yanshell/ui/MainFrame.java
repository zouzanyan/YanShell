package com.yanshell.ui;

import com.yanshell.core.ConnectionFolder;
import com.yanshell.core.ConnectionProfile;
import com.yanshell.core.ConnectionStore;
import com.yanshell.core.TerminalOutput;
import com.yanshell.sftp.SftpService;
import com.yanshell.ssh.ExecService;
import com.yanshell.ssh.KnownHostsVerifier;
import com.yanshell.ssh.SSHClient;
import com.yanshell.terminal.SshTtyConnector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

/**
 * Main window — FinalShell-style multi-session layout:
 *
 * <pre>
 *  +--------------------------------------------------------+
 *  | 连接(F)  帮助(H)                                         |
 *  +-----------+--------------------------------------------+
 *  | 连接   [+] | [web-1 ×] [web-2 ×] [db ×] [+]             |
 *  | ▸ 生产    +--------------------------------------------+
 *  |   web-1  | [终端] [文件管理]                             |
 *  |   web-2  |   ... session content ...                    |
 *  | ▸ 测试    |                                              |
 *  +-----------+--------------------------------------------+
 *  | ● 已连接   web-1 — root@10.0.0.1:22                     |
 *  +--------------------------------------------------------+
 * </pre>
 *
 * <p>Each {@link SessionView} tab owns its own SSH client, terminal, and
 * SFTP — they're independent. Closing a tab tears down only that session.</p>
 */
public final class MainFrame extends JFrame {

    private static final Logger log = LoggerFactory.getLogger(MainFrame.class);
    private static final String CARD_WELCOME  = "welcome";
    private static final String CARD_SESSIONS = "sessions";

    private final SessionPanel sessionPanel = new SessionPanel();
    private final JTabbedPane sessionTabs = new JTabbedPane();
    private final CardLayout centerCards = new CardLayout();
    private final JPanel centerHost = new JPanel(centerCards);

    // Left-sidebar collapse state.
    private JSplitPane bodySplit;
    private JComponent collapsedBar;
    private int savedDividerLocation = 240;

    private final StatusLed statusLed = new StatusLed();
    private final JLabel statusLabel = new JLabel("未连接");
    private final JLabel termInfoLabel = new JLabel("xterm-256color   UTF-8");

    private final ConnectionStore store = new ConnectionStore();

    public MainFrame() {
        super("Yanshell");
        // We control the exit ourselves so we can ask for confirmation
        // when sessions are still open.
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        setSize(1180, 740);
        setMinimumSize(new Dimension(720, 420));
        setLocationRelativeTo(null);

        setLayout(new BorderLayout());
        setJMenuBar(buildMenuBar());
        add(buildBody(),    BorderLayout.CENTER);
        add(buildStatus(),  BorderLayout.SOUTH);

        wireActions();
        statusLed.setState(StatusLed.State.DISCONNECTED);

        // Load the persisted connection tree, save on every change.
        sessionPanel.setRoot(store.load());
        sessionPanel.setOnChanged(() -> store.save(sessionPanel.getRoot()));

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                if (confirmAppExit()) {
                    disposeAllSessions();
                    dispose();
                    System.exit(0);
                }
            }
        });
    }

    /**
     * Ask before exiting if any sessions are still open, otherwise quit
     * straight away. Returns {@code true} if the app should exit.
     */
    private boolean confirmAppExit() {
        int openTabs = sessionTabs.getTabCount();
        String msg = openTabs > 0
                ? "确定要退出 Yanshell 吗？当前有 " + openTabs + " 个会话将被关闭。"
                : "确定要退出 Yanshell 吗？";
        int r = JOptionPane.showConfirmDialog(this,
                msg,
                "退出",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.QUESTION_MESSAGE);
        return r == JOptionPane.OK_OPTION;
    }

    // ---- layout pieces ---------------------------------------------------

    private JMenuBar buildMenuBar() {
        JMenuBar menuBar = new JMenuBar();

        // Logo placeholder on the left
        JLabel logoLabel = new JLabel("  Y.Sh  ");
        logoLabel.setFont(logoLabel.getFont().deriveFont(Font.BOLD, 13f));
        logoLabel.setForeground(UIManager.getColor("Component.accentColor"));
        menuBar.add(logoLabel);
        menuBar.add(Box.createHorizontalStrut(8));

        // 连接 menu
        JMenu connMenu = new JMenu("连接(F)");
        connMenu.setMnemonic('F');
        JMenuItem newConnItem = new JMenuItem("新建连接");
        newConnItem.addActionListener(e -> onNewProfile());
        connMenu.add(newConnItem);
        connMenu.addSeparator();
        JMenuItem exitItem = new JMenuItem("退出");
        exitItem.addActionListener(e -> {
            if (confirmAppExit()) {
                disposeAllSessions();
                dispose();
                System.exit(0);
            }
        });
        connMenu.add(exitItem);
        menuBar.add(connMenu);

        // 工具 menu
        JMenu toolsMenu = new JMenu("工具(T)");
        toolsMenu.setMnemonic('T');
        JMenuItem toolboxItem = new JMenuItem("工具箱...");
        toolboxItem.addActionListener(e -> onOpenToolbox());
        toolsMenu.add(toolboxItem);
        menuBar.add(toolsMenu);

        // 设置 menu
        JMenu settingsMenu = new JMenu("设置(S)");
        settingsMenu.setMnemonic('S');
        JMenuItem themeItem = new JMenuItem("主题设置...");
        themeItem.addActionListener(e -> onThemeSettings());
        settingsMenu.add(themeItem);
        menuBar.add(settingsMenu);

        // 帮助 menu
        JMenu helpMenu = new JMenu("帮助(H)");
        helpMenu.setMnemonic('H');
        JMenuItem aboutItem = new JMenuItem("关于 Yanshell");
        aboutItem.addActionListener(e -> JOptionPane.showMessageDialog(this,
                "Yanshell — 轻量 SSH 终端客户端\n版本 1.0",
                "关于", JOptionPane.INFORMATION_MESSAGE));
        helpMenu.add(aboutItem);
        menuBar.add(helpMenu);

        return menuBar;
    }

    private JComponent buildBody() {
        // Welcome card -- shown when no sessions are open.
        JPanel welcome = new JPanel(new GridBagLayout());
        welcome.setBackground(UIManager.getColor("Panel.background"));
        GridBagConstraints g = new GridBagConstraints();
        g.gridx = 0; g.gridy = 0; g.insets = new Insets(0, 0, 8, 0);
        JLabel logo = new JLabel("Y.Sh");
        logo.setFont(logo.getFont().deriveFont(Font.BOLD, 28f));
        logo.setForeground(UIManager.getColor("Component.accentColor"));
        welcome.add(logo, g);
        g.gridy = 1;
        JLabel hint = new JLabel("Click + in connection list to add, or double-click to open.");
        hint.setForeground(UIManager.getColor("Label.disabledForeground"));
        welcome.add(hint, g);

        sessionTabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        sessionTabs.putClientProperty("JTabbedPane.tabClosable", true);
        sessionTabs.putClientProperty("JTabbedPane.tabCloseToolTipText", "Close");
        sessionTabs.putClientProperty("JTabbedPane.tabInsets", new Insets(4, 40, 4, 40));
        sessionTabs.putClientProperty("JTabbedPane.tabCloseCallback",
                (java.util.function.BiConsumer<JTabbedPane, Integer>) (tabs, idx) -> {
                    if (tabs.getComponentAt(idx) instanceof SessionView v) {
                        confirmAndCloseSession(v);
                    }
                });
        sessionTabs.addChangeListener(e -> updateForActiveSession());

        centerHost.setBackground(UIManager.getColor("Panel.background"));
        centerHost.add(welcome,     CARD_WELCOME);
        centerHost.add(sessionTabs, CARD_SESSIONS);
        centerCards.show(centerHost, CARD_WELCOME);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, sessionPanel, centerHost);
        split.setBorder(null);
        split.setDividerSize(4);
        split.setContinuousLayout(true);
        split.setDividerLocation(240);
        bodySplit = split;
        collapsedBar = buildCollapsedBar();
        return split;
    }

    /**
     * Thin vertical strip shown in place of the sidebar when collapsed.
     * Holds a single expand button that restores the connection list.
     */
    private JComponent buildCollapsedBar() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBackground(UIManager.getColor("Panel.background"));
        bar.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1,
                UIManager.getColor("Separator.foreground")));
        bar.setPreferredSize(new Dimension(28, 0));

        JButton expand = new JButton("⟩");
        expand.setToolTipText("展开连接列表");
        expand.setMargin(new Insets(4, 4, 4, 4));
        expand.setFocusPainted(false);
        expand.putClientProperty("JButton.buttonType", "roundRect");
        expand.addActionListener(e -> setSidebarCollapsed(false));

        JPanel top = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 6));
        top.setOpaque(false);
        top.add(expand);
        bar.add(top, BorderLayout.NORTH);
        return bar;
    }

    /** Collapse the sidebar to a thin strip, or restore it to its prior width. */
    private void setSidebarCollapsed(boolean collapsed) {
        if (collapsed) {
            savedDividerLocation = bodySplit.getDividerLocation();
            bodySplit.setLeftComponent(collapsedBar);
            bodySplit.setDividerSize(0);
            bodySplit.setDividerLocation(collapsedBar.getPreferredSize().width);
        } else {
            bodySplit.setLeftComponent(sessionPanel);
            bodySplit.setDividerSize(4);
            bodySplit.setDividerLocation(savedDividerLocation);
        }
    }

    private JComponent buildStatus() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBackground(UIManager.getColor("Panel.background"));
        bar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, UIManager.getColor("Separator.foreground")),
                BorderFactory.createEmptyBorder(4, 10, 4, 10)));
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        left.setOpaque(false);
        left.add(statusLed);
        left.add(statusLabel);
        bar.add(left, BorderLayout.WEST);
        termInfoLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
        bar.add(termInfoLabel, BorderLayout.EAST);
        return bar;
    }

    // ---- actions ---------------------------------------------------------

    private void wireActions() {
        sessionPanel.setOnOpen(this::doConnect);
        sessionPanel.setOnNewProfile(this::onNewProfile);
        sessionPanel.setOnAddFolder(this::onNewFolder);
        sessionPanel.setOnEdit(this::onEditNode);
        sessionPanel.setOnDelete(this::onDeleteNode);
        sessionPanel.setOnCollapse(() -> setSidebarCollapsed(true));
    }

    private void onNewProfile() {
        SessionDialog d = new SessionDialog(this, null);
        d.setVisible(true);
        ConnectionProfile item = d.getResult();
        if (item != null) sessionPanel.addProfile(item);
    }

    private void onNewFolder() {
        String name = FolderNameDialog.ask(this, null);
        if (name != null) sessionPanel.addFolder(new ConnectionFolder(name));
    }

    private void onThemeSettings() {
        SettingsDialog d = new SettingsDialog(this);
        d.setVisible(true);
    }

    private void onOpenToolbox() {
        ToolboxDialog d = new ToolboxDialog(this);
        d.setVisible(true);
    }

    private void onEditNode() {
        var sel = sessionPanel.getSelected();
        if (sel == null) return;
        if (sel instanceof ConnectionProfile p) {
            SessionDialog d = new SessionDialog(this, p);
            d.setVisible(true);
            ConnectionProfile updated = d.getResult();
            if (updated != null) sessionPanel.replaceSelected(updated);
        } else if (sel instanceof ConnectionFolder f) {
            String name = FolderNameDialog.ask(this, f.getName());
            if (name != null) sessionPanel.replaceSelected(new ConnectionFolder(name));
        }
    }

    private void onDeleteNode() {
        var sel = sessionPanel.getSelected();
        if (sel == null) return;
        String label = sel instanceof ConnectionFolder ? "Folder" : "Connection";
        int r = JOptionPane.showConfirmDialog(this,
                "Delete " + label + " \"" + sel.getName() + "\"?",
                "Confirm", JOptionPane.OK_CANCEL_OPTION);
        if (r == JOptionPane.OK_OPTION) sessionPanel.removeSelected();
    }

    /** Open a brand-new session for the given profile (always a new tab). */
    private void doConnect(ConnectionProfile profile) {
        var cfg = profile.getConfig();
        statusLed.setState(StatusLed.State.CONNECTING);
        setStatus("正在连接 " + cfg.getHost() + ":" + cfg.getPort() + " ...");

        KnownHostsVerifier.HostKeyPrompt prompt = (host, port, fingerprint, changed) -> {
            var result = new java.util.concurrent.atomic.AtomicBoolean(false);
            var latch = new java.util.concurrent.CountDownLatch(1);
            SwingUtilities.invokeLater(() -> {
                try {
                    String title = changed ? "主机密钥已变更！" : "首次连接 — 确认主机密钥";
                    String msg;
                    if (changed) {
                        msg = "<html><b>警告：主机密钥不匹配！</b><br><br>"
                                + "服务器 <b>" + host + ":" + port + "</b> 的密钥已变更。<br>"
                                + "这可能意味着有人正在进行中间人攻击，<br>"
                                + "或者服务器管理员更换了主机密钥。<br><br>"
                                + "新密钥指纹：<br><code>" + fingerprint + "</code><br><br>"
                                + "确定要继续连接吗？</html>";
                    } else {
                        msg = "<html>首次连接到 <b>" + host + ":" + port + "</b>。<br><br>"
                                + "主机密钥指纹：<br><code>" + fingerprint + "</code><br><br>"
                                + "确定信任此主机并继续连接吗？</html>";
                    }
                    int optionType = changed ? JOptionPane.YES_NO_OPTION : JOptionPane.OK_CANCEL_OPTION;
                    int icon = changed ? JOptionPane.WARNING_MESSAGE : JOptionPane.INFORMATION_MESSAGE;
                    int r = JOptionPane.showConfirmDialog(MainFrame.this,
                            msg, title, optionType, icon);
                    result.set(r == JOptionPane.OK_OPTION || r == JOptionPane.YES_OPTION);
                } finally {
                    latch.countDown();
                }
            });
            try {
                latch.await();
            } catch (InterruptedException ignored) {}
            return result.get();
        };

        SSHClient client = new SSHClient(cfg, prompt);
        SshTtyConnector connector = new SshTtyConnector(client);
        TerminalOutput output = connector::feed;
        client.setOutput(output);

        Thread t = new Thread(() -> {
            try {
                client.connect();
                SftpService sftp = null;
                try {
                    sftp = new SftpService(client);
                } catch (Exception ex) {
                    log.warn("SFTP unavailable: {}", ex.getMessage());
                }
                ExecService exec = null;
                try {
                    exec = new ExecService(client);
                } catch (Exception ex) {
                    log.warn("Exec unavailable: {}", ex.getMessage());
                }
                final SftpService sftpFinal = sftp;
                final ExecService execFinal = exec;
                SwingUtilities.invokeLater(() -> {
                    SessionView view = new SessionView(profile, client, connector, sftpFinal, execFinal);
                    addSessionTab(view);
                    centerCards.show(centerHost, CARD_SESSIONS);
                    sessionTabs.setSelectedComponent(view);
                    updateForActiveSession();
                });
            } catch (Exception ex) {
                log.error("connect failed", ex);
                client.disconnect();
                SwingUtilities.invokeLater(() -> {
                    statusLed.setState(StatusLed.State.ERROR);
                    setStatus("连接失败: " + ex.getMessage());
                    JOptionPane.showMessageDialog(MainFrame.this,
                            ex.getMessage(),
                            "连接失败",
                            JOptionPane.ERROR_MESSAGE);
                });
            }
        }, "ssh-connect-" + profile.getName());
        t.setDaemon(true);
        t.start();
    }

    private void addSessionTab(SessionView view) {
        sessionTabs.addTab(view.getProfile().getName(), view);
    }

    /** Ask the user, then close the session if confirmed. */
    private void confirmAndCloseSession(SessionView view) {
        int r = JOptionPane.showConfirmDialog(this,
                "Close session \"" + view.getProfile().getName() + "\" and disconnect?",
                "Close Session",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.QUESTION_MESSAGE);
        if (r == JOptionPane.OK_OPTION) {
            closeSession(view);
        }
    }

    private void closeSession(SessionView view) {
        int idx = sessionTabs.indexOfComponent(view);
        if (idx >= 0) sessionTabs.removeTabAt(idx);
        view.dispose();
        if (sessionTabs.getTabCount() == 0) {
            centerCards.show(centerHost, CARD_WELCOME);
        }
        updateForActiveSession();
    }

    private void disposeAllSessions() {
        for (int i = sessionTabs.getTabCount() - 1; i >= 0; i--) {
            if (sessionTabs.getComponentAt(i) instanceof SessionView v) {
                v.dispose();
            }
        }
        sessionTabs.removeAll();
    }

    /** Refresh status bar + buttons based on the currently selected tab. */
    private void updateForActiveSession() {
        SessionView active = (sessionTabs.getSelectedComponent() instanceof SessionView v) ? v : null;
        if (active != null) {
            statusLed.setState(StatusLed.State.CONNECTED);
            setStatus(active.getStatusLine());
        } else {
            statusLed.setState(StatusLed.State.DISCONNECTED);
            setStatus("未连接");
        }
    }

    private void setStatus(String text) {
        if (SwingUtilities.isEventDispatchThread()) {
            statusLabel.setText(text);
        } else {
            SwingUtilities.invokeLater(() -> statusLabel.setText(text));
        }
    }

    /** Tiny colored circle indicating connection state. */
    private static final class StatusLed extends JPanel {
        enum State {
            DISCONNECTED(new Color(0x9E9E9E)),
            CONNECTING(new Color(0xF5A623)),
            CONNECTED(new Color(0x4CAF50)),
            ERROR(new Color(0xE53935));

            final Color color;
            State(Color c) { this.color = c; }
        }

        private State state = State.DISCONNECTED;

        StatusLed() {
            setOpaque(false);
            setPreferredSize(new Dimension(12, 12));
        }

        void setState(State s) {
            this.state = s;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int d = Math.min(getWidth(), getHeight()) - 2;
            int x = (getWidth() - d) / 2;
            int y = (getHeight() - d) / 2;
            g2.setColor(state.color);
            g2.fillOval(x, y, d, d);
            g2.dispose();
        }
    }
}
