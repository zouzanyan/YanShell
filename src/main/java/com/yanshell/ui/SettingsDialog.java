package com.yanshell.ui;

import com.yanshell.util.ThemeManager;

import javax.swing.*;
import java.awt.*;
import java.util.Map;

/**
 * Settings dialog for application preferences.
 */
public class SettingsDialog extends JDialog {

    private final ThemeManager themeManager = ThemeManager.getInstance();
    private JComboBox<ThemeItem> themeCombo;
    private boolean applied = false;

    public SettingsDialog(JFrame owner) {
        super(owner, "设置", true);
        setSize(420, 200);
        setResizable(false);
        setLocationRelativeTo(owner);
        initUI();
    }

    private void initUI() {
        JPanel content = new JPanel(new BorderLayout(0, 16));
        content.setBorder(BorderFactory.createEmptyBorder(20, 24, 16, 24));

        // Theme selection panel
        JPanel themePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        JLabel themeLabel = new JLabel("主题外观：");
        themePanel.add(themeLabel);

        themeCombo = new JComboBox<>();
        Map<String, String> themes = themeManager.getAvailableThemes();
        String currentTheme = themeManager.getCurrentTheme();
        int selectedIndex = 0;
        int index = 0;
        for (Map.Entry<String, String> entry : themes.entrySet()) {
            ThemeItem item = new ThemeItem(entry.getKey(), entry.getValue());
            themeCombo.addItem(item);
            if (entry.getKey().equals(currentTheme)) {
                selectedIndex = index;
            }
            index++;
        }
        themeCombo.setSelectedIndex(selectedIndex);
        themePanel.add(themeCombo);

        content.add(themePanel, BorderLayout.CENTER);

        // Button panel
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        JButton applyBtn = new JButton("应用");
        JButton cancelBtn = new JButton("取消");

        applyBtn.addActionListener(e -> onApply());
        cancelBtn.addActionListener(e -> dispose());

        buttonPanel.add(applyBtn);
        buttonPanel.add(cancelBtn);
        content.add(buttonPanel, BorderLayout.SOUTH);

        setContentPane(content);
    }

    private void onApply() {
        ThemeItem selected = (ThemeItem) themeCombo.getSelectedItem();
        if (selected != null) {
            boolean success = themeManager.applyTheme(selected.className());
            if (success) {
                applied = true;
                JOptionPane.showMessageDialog(this,
                        "主题已切换为：" + selected.displayName(),
                        "主题切换", JOptionPane.INFORMATION_MESSAGE);
                dispose();
            } else {
                JOptionPane.showMessageDialog(this,
                        "主题切换失败，请重试。",
                        "错误", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    public boolean isApplied() {
        return applied;
    }

    /** Simple record for combo box items. */
    private record ThemeItem(String className, String displayName) {
        @Override
        public String toString() {
            return displayName;
        }
    }
}
