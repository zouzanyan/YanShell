package com.yanshell.ui;

import com.yanshell.ssh.ExecService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * System monitor panel — periodically polls the remote host and displays
 * metrics in a 2×3 compact card grid, FinalShell-style.
 *
 * <pre>
 *  +-----------------------------------------------------------------+
 *  | 主机: web-1  |  OS: Linux 5.15  |  运行: 32天                     |
 *  +-----------------------------------------------------------------+
 *  |  CPU 42%      内存 71%         磁盘 78%                          |
 *  |  [████░░]     [██████░]        [██████░]                        |
 *  |               5.6 / 8.0 GB    62 / 80 GB                        |
 *  +-----------------------------------------------------------------+
 *  |  交换 0%       网络 ↓12K ↑3K    负载 0.15 0.22 0.18              |
 *  |  [░░░░░░]     512M / 2.0G      运行/总进程: 1/375                 |
 *  +-----------------------------------------------------------------+
 * </pre>
 */
public final class MonitorPanel extends JPanel {

    private static final Logger log = LoggerFactory.getLogger(MonitorPanel.class);
    private static final Color CLR_GOOD = new Color(0x2EA043);
    private static final Color CLR_WARN = new Color(0xD29922);
    private static final Color CLR_HOT  = new Color(0xCF222E);
    private static final long REFRESH_INTERVAL_SEC = 5;

    // Single command that gathers all stats in one SSH round-trip.
    private static final String STATS_CMD =
            "uname -srm; cat /proc/uptime; grep '^cpu ' /proc/stat; free -b; df -B1 /; cat /proc/loadavg; cat /proc/net/dev; ps -e --no-headers | wc -l";

    private volatile ExecService exec;
    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> future;
    private final AtomicBoolean running = new AtomicBoolean(false);

    // Previous snapshots for delta calculations.
    private long[] prevCpu;
    private long prevNetTime;
    private long prevNetRx;
    private long prevNetTx;

    // Server info labels
    private final JLabel hostnameLabel = new JLabel("—");
    private final JLabel osLabel = new JLabel("—");
    private final JLabel uptimeLabel = new JLabel("—");

    // Row 1: CPU / 内存 / 磁盘
    private final JProgressBar cpuBar  = progressBar();
    private final JLabel cpuPct        = new JLabel("—");
    private final JLabel cpuDetail     = new JLabel(" ");
    private final JProgressBar memBar  = progressBar();
    private final JLabel memPct        = new JLabel("—");
    private final JLabel memDetail     = new JLabel(" ");
    private final JProgressBar diskBar = progressBar();
    private final JLabel diskPct       = new JLabel("—");
    private final JLabel diskDetail    = new JLabel(" ");

    // Row 2: 交换 / 网络 / 负载
    private final JProgressBar swapBar = progressBar();
    private final JLabel swapPct       = new JLabel("—");
    private final JLabel swapDetail    = new JLabel(" ");
    private final JProgressBar netBar  = progressBar();
    private final JLabel netPct        = new JLabel("—");
    private final JLabel netDetail     = new JLabel(" ");
    private final JLabel loadLabel     = new JLabel("—");
    private final JLabel loadDetail    = new JLabel(" ");

    public MonitorPanel() {
        super(new BorderLayout(0, 8));
        setBorder(BorderFactory.createEmptyBorder(12, 16, 12, 16));
        setBackground(UIManager.getColor("Panel.background"));
        add(buildInfoBar(), BorderLayout.NORTH);
        add(buildMetrics(), BorderLayout.CENTER);
    }

    /** Set the hostname displayed in the info bar. */
    public void setHostname(String name) {
        hostnameLabel.setText(name);
    }

    /** Start periodic polling. Safe to call multiple times. */
    public void attach(ExecService exec) {
        detach();
        if (exec == null) return;
        this.exec = exec;
        this.prevCpu = null;
        this.prevNetRx = 0;
        this.prevNetTx = 0;
        this.prevNetTime = 0;
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "monitor-refresh");
            t.setDaemon(true);
            return t;
        });
        running.set(true);
        future = scheduler.scheduleWithFixedDelay(
                this::safeRefresh, 0, REFRESH_INTERVAL_SEC, TimeUnit.SECONDS);
    }

    /** Stop polling and release resources. */
    public void detach() {
        running.set(false);
        if (future != null) {
            future.cancel(false);
            future = null;
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        exec = null;
        prevCpu = null;
    }

    // ---- UI construction ---------------------------------------------------

    private JPanel buildInfoBar() {
        JPanel bar = new JPanel(new GridBagLayout());
        bar.setBackground(UIManager.getColor("Panel.background"));
        bar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(UIManager.getColor("Separator.foreground")),
                BorderFactory.createEmptyBorder(8, 14, 8, 14)));

        GridBagConstraints c = new GridBagConstraints();
        c.gridy = 0;
        c.insets = new Insets(0, 0, 0, 18);
        c.anchor = GridBagConstraints.WEST;

        c.gridx = 0; bar.add(boldLabel("主机"), c);
        c.gridx = 1; bar.add(hostnameLabel, c);
        c.gridx = 2; bar.add(boldLabel("系统"), c);
        c.gridx = 3; bar.add(osLabel, c);
        c.gridx = 4; bar.add(boldLabel("运行"), c);
        c.gridx = 5; c.insets = new Insets(0, 0, 0, 0);
        bar.add(uptimeLabel, c);
        return bar;
    }

    private JPanel buildMetrics() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setOpaque(false);
        GridBagConstraints c = new GridBagConstraints();
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1.0;

        // Row 0: CPU | 内存 | 磁盘
        c.gridy = 0;
        c.insets = new Insets(0, 0, 6, 8);
        c.gridx = 0; p.add(compactMetric("CPU",  cpuBar,  cpuPct,  cpuDetail),  c);
        c.gridx = 1; p.add(compactMetric("内存", memBar,  memPct,  memDetail),  c);
        c.gridx = 2; c.insets = new Insets(0, 0, 6, 0);
        p.add(compactMetric("磁盘", diskBar, diskPct, diskDetail), c);

        // Row 1: 交换 | 网络 | 负载
        c.gridy = 1;
        c.insets = new Insets(0, 0, 0, 8);
        c.gridx = 0; p.add(compactMetric("交换", swapBar, swapPct, swapDetail), c);
        c.gridx = 1; p.add(netMetric(), c);
        c.gridx = 2; c.insets = new Insets(0, 0, 0, 0);
        p.add(loadMetric(), c);

        // Fill remaining vertical space
        c.gridx = 0; c.gridy = 2; c.gridwidth = 3;
        c.weighty = 1.0;
        p.add(Box.createVerticalGlue(), c);
        return p;
    }

    /** Standard compact metric card: title + pct | bar | detail. */
    private static JPanel compactMetric(String title, JProgressBar bar, JLabel pctLabel, JLabel detail) {
        JPanel row = new JPanel(new GridBagLayout());
        row.setBackground(UIManager.getColor("Panel.background"));
        row.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(UIManager.getColor("Separator.foreground")),
                BorderFactory.createEmptyBorder(6, 10, 6, 10)));

        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0; c.gridy = 0;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(0, 0, 0, 6);
        row.add(compactLabel(title), c);

        c.gridx = 1; c.gridy = 0;
        c.insets = new Insets(0, 0, 0, 6);
        pctLabel.setFont(pctLabel.getFont().deriveFont(Font.BOLD, 12f));
        pctLabel.setForeground(UIManager.getColor("Label.foreground"));
        row.add(pctLabel, c);

        c.gridx = 0; c.gridy = 1; c.gridwidth = 2;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1.0;
        c.insets = new Insets(2, 0, 1, 0);
        row.add(bar, c);

        c.gridx = 0; c.gridy = 2; c.gridwidth = 2;
        c.fill = GridBagConstraints.NONE;
        c.anchor = GridBagConstraints.EAST;
        c.insets = new Insets(0, 0, 0, 0);
        detail.setFont(detail.getFont().deriveFont(10f));
        detail.setForeground(UIManager.getColor("Label.disabledForeground"));
        row.add(detail, c);
        return row;
    }

    /** Network card: shows ↓RX / ↑TX speeds, no progress bar. */
    private JPanel netMetric() {
        JPanel row = new JPanel(new GridBagLayout());
        row.setBackground(UIManager.getColor("Panel.background"));
        row.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(UIManager.getColor("Separator.foreground")),
                BorderFactory.createEmptyBorder(6, 10, 6, 10)));

        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0; c.gridy = 0;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(0, 0, 0, 6);
        row.add(compactLabel("网络"), c);

        c.gridx = 1; c.gridy = 0;
        c.insets = new Insets(0, 0, 0, 6);
        netPct.setFont(netPct.getFont().deriveFont(Font.BOLD, 12f));
        netPct.setForeground(UIManager.getColor("Label.foreground"));
        row.add(netPct, c);

        c.gridx = 0; c.gridy = 1; c.gridwidth = 2;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1.0;
        c.insets = new Insets(2, 0, 1, 0);
        row.add(netBar, c);

        c.gridx = 0; c.gridy = 2; c.gridwidth = 2;
        c.fill = GridBagConstraints.NONE;
        c.anchor = GridBagConstraints.EAST;
        c.insets = new Insets(0, 0, 0, 0);
        netDetail.setFont(netDetail.getFont().deriveFont(10f));
        netDetail.setForeground(UIManager.getColor("Label.disabledForeground"));
        row.add(netDetail, c);
        return row;
    }

    /** Load card: text-only, no progress bar. */
    private JPanel loadMetric() {
        JPanel row = new JPanel(new GridBagLayout());
        row.setBackground(UIManager.getColor("Panel.background"));
        row.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(UIManager.getColor("Separator.foreground")),
                BorderFactory.createEmptyBorder(6, 10, 6, 10)));

        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0; c.gridy = 0;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(0, 0, 0, 6);
        row.add(compactLabel("负载"), c);

        c.gridx = 1; c.gridy = 0;
        c.insets = new Insets(0, 0, 0, 6);
        loadLabel.setFont(loadLabel.getFont().deriveFont(Font.BOLD, 12f));
        loadLabel.setForeground(UIManager.getColor("Label.foreground"));
        row.add(loadLabel, c);

        // Spacer to match other cards' bar height
        c.gridx = 0; c.gridy = 1; c.gridwidth = 2;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1.0;
        c.insets = new Insets(2, 0, 1, 0);
        row.add(Box.createVerticalStrut(8), c);

        c.gridx = 0; c.gridy = 2; c.gridwidth = 2;
        c.fill = GridBagConstraints.NONE;
        c.anchor = GridBagConstraints.EAST;
        c.insets = new Insets(0, 0, 0, 0);
        loadDetail.setFont(loadDetail.getFont().deriveFont(10f));
        loadDetail.setForeground(UIManager.getColor("Label.disabledForeground"));
        row.add(loadDetail, c);
        return row;
    }

    private static JLabel boldLabel(String text) {
        JLabel l = new JLabel(text);
        l.setFont(l.getFont().deriveFont(Font.BOLD, 13f));
        l.setForeground(UIManager.getColor("Label.foreground"));
        return l;
    }

    private static JLabel compactLabel(String text) {
        JLabel l = new JLabel(text);
        l.setFont(l.getFont().deriveFont(Font.BOLD, 11f));
        l.setForeground(UIManager.getColor("Label.disabledForeground"));
        return l;
    }

    private static JProgressBar progressBar() {
        JProgressBar bar = new JProgressBar(0, 100);
        bar.setStringPainted(false);
        bar.setPreferredSize(new Dimension(100, 8));
        bar.setForeground(CLR_GOOD);
        bar.setBackground(UIManager.getColor("ProgressBar.background"));
        return bar;
    }

    /** Apply color threshold to a progress bar. */
    private static void applyBarColor(JProgressBar bar, int pct) {
        if (pct >= 90) bar.setForeground(CLR_HOT);
        else if (pct >= 75) bar.setForeground(CLR_WARN);
        else bar.setForeground(CLR_GOOD);
    }

    // ---- data fetch + parse ------------------------------------------------

    private void safeRefresh() {
        if (!running.get() || exec == null) return;
        try {
            refresh();
        } catch (Exception ex) {
            log.debug("monitor refresh failed: {}", ex.getMessage());
        }
    }

    private void refresh() throws IOException {
        ExecService svc = exec;
        if (svc == null) return;
        String raw = svc.exec(STATS_CMD);
        if (raw == null || raw.isBlank()) return;

        String[] lines = raw.split("\n");

        // Parse sections using marker-based approach.
        String sys = null;
        StringBuilder cpuBuf = new StringBuilder();
        StringBuilder memBuf = new StringBuilder();
        StringBuilder diskBuf = new StringBuilder();
        String loadRaw = null;
        StringBuilder netBuf = new StringBuilder();
        String procCount = null;

        // Phase: 0=sys, 1=cpu, 2=mem, 3=disk, 4=loadavg, 5=net, 6=ps
        int phase = 0;
        int sysCount = 0;

        for (String line : lines) {
            String t = line.trim();
            if (t.isEmpty()) continue;

            switch (phase) {
                case 0 -> {
                    if (sysCount == 0) {
                        sys = t;
                        sysCount++;
                    } else {
                        sys = sys + "\n" + t;
                        sysCount++;
                        phase = 1;
                    }
                }
                case 1 -> {
                    if (t.startsWith("cpu")) cpuBuf.append(t).append('\n');
                    else { phase = 2; memBuf.append(t).append('\n'); }
                }
                case 2 -> {
                    if (t.startsWith("Filesystem")) { phase = 3; diskBuf.append(t).append('\n'); }
                    else memBuf.append(t).append('\n');
                }
                case 3 -> {
                    if (t.startsWith("/dev/") || t.startsWith("overlay") || t.startsWith("tmpfs"))
                        diskBuf.append(t).append('\n');
                    else if (!t.startsWith("Filesystem")) {
                        phase = 4;
                        loadRaw = t;
                    }
                }
                case 4 -> {
                    if (loadRaw == null) loadRaw = t;
                    else { phase = 5; netBuf.append(t).append('\n'); }
                }
                case 5 -> {
                    // After net/dev headers, check if line looks like a number (ps output)
                    if (t.matches("\\d+")) { phase = 6; procCount = t; }
                    else netBuf.append(t).append('\n');
                }
                case 6 -> {} // ps output already captured, ignore any remainder
            }
        }

        String cpuOut = cpuBuf.length() > 0 ? cpuBuf.toString() : null;
        String memOut = memBuf.length() > 0 ? memBuf.toString() : null;
        String diskOut = diskBuf.length() > 0 ? diskBuf.toString() : null;
        String netOut = netBuf.length() > 0 ? netBuf.toString() : null;

        // Parse on background thread.
        String osVal = null, uptimeVal = null;
        if (sys != null) {
            String[] sl = sys.split("\n");
            if (sl.length >= 1) osVal = sl[0];
            if (sl.length >= 2) {
                try {
                    long sec = (long) Double.parseDouble(sl[1].trim().split("\\s+")[0]);
                    uptimeVal = formatUptime(sec);
                } catch (NumberFormatException ignored) {}
            }
        }

        int cpuVal = parseCpu(cpuOut);
        MemInfo memInfo = parseMem(memOut);
        DiskInfo diskInfo = parseDisk(diskOut);
        SwapInfo swapInfo = parseSwap(memOut);
        NetInfo netInfo = parseNet(netOut);
        LoadInfo loadInfo = parseLoad(loadRaw);

        // Single EDT update.
        final String osF = osVal;
        final String uptimeF = uptimeVal;
        final int cpuF = cpuVal;
        final MemInfo memF = memInfo;
        final DiskInfo diskF = diskInfo;
        final SwapInfo swapF = swapInfo;
        final NetInfo netF = netInfo;
        final LoadInfo loadF = loadInfo;

        SwingUtilities.invokeLater(() -> {
            if (osF != null) osLabel.setText(osF);
            if (uptimeF != null) uptimeLabel.setText(uptimeF);

            if (cpuF >= 0) {
                applyBarColor(cpuBar, cpuF);
                cpuBar.setValue(cpuF);
                cpuPct.setText(cpuF + "%");
            }

            if (memF != null) {
                applyBarColor(memBar, memF.pct);
                memBar.setValue(memF.pct);
                memPct.setText(memF.pct + "%");
                memDetail.setText(formatBytes(memF.used) + " / " + formatBytes(memF.total));
            }

            if (diskF != null) {
                applyBarColor(diskBar, diskF.pct);
                diskBar.setValue(diskF.pct);
                diskPct.setText(diskF.pct + "%");
                diskDetail.setText(formatBytes(diskF.used) + " / " + formatBytes(diskF.total));
            }

            if (swapF != null) {
                applyBarColor(swapBar, swapF.pct);
                swapBar.setValue(swapF.pct);
                swapPct.setText(swapF.pct + "%");
                swapDetail.setText(formatBytes(swapF.used) + " / " + formatBytes(swapF.total));
            }

            if (netF != null) {
                netPct.setText("↓" + formatSpeed(netF.rx) + " ↑" + formatSpeed(netF.tx));
                netBar.setValue(0); // net bar not used as percentage
                netBar.setForeground(new Color(0x2F6FEB));
                netDetail.setText("RX " + formatBytes(netF.rxTotal) + "  TX " + formatBytes(netF.txTotal));
            }

            if (loadF != null) {
                loadLabel.setText(String.format("%.2f %.2f %.2f", loadF.load1, loadF.load5, loadF.load15));
                loadDetail.setText("运行/总进程: " + loadF.procs);
            }
        });
    }

    // ---- CPU ----------------------------------------------------------------

    private static final int IDX_IDLE   = 3;
    private static final int IDX_IOWAIT = 4;

    private int parseCpu(String out) {
        if (out == null) return -1;
        long[] curr = null;
        for (String line : out.split("\n")) {
            if (line.trim().startsWith("cpu ")) {
                curr = parseCpuTimes(line.trim());
                break;
            }
        }
        if (curr == null) return -1;
        if (prevCpu == null || prevCpu.length != curr.length) {
            prevCpu = curr.clone();
            return -1;
        }
        long totalDelta = 0;
        for (int i = 0; i < curr.length; i++) totalDelta += (curr[i] - prevCpu[i]);
        long idleDelta = 0;
        if (curr.length > IDX_IOWAIT) {
            idleDelta = (curr[IDX_IDLE] - prevCpu[IDX_IDLE])
                      + (curr[IDX_IOWAIT] - prevCpu[IDX_IOWAIT]);
        } else if (curr.length > IDX_IDLE) {
            idleDelta = curr[IDX_IDLE] - prevCpu[IDX_IDLE];
        }
        prevCpu = curr.clone();
        if (totalDelta <= 0) return 0;
        return (int) Math.min(100, Math.max(0, (totalDelta - idleDelta) * 100L / totalDelta));
    }

    private static long[] parseCpuTimes(String line) {
        String[] parts = line.trim().split("\\s+");
        if (parts.length < 5) return null;
        try {
            long[] vals = new long[parts.length - 1];
            for (int i = 1; i < parts.length; i++) vals[i - 1] = Long.parseLong(parts[i]);
            return vals;
        } catch (NumberFormatException e) { return null; }
    }

    // ---- Memory / Swap / Disk -----------------------------------------------

    private record MemInfo(int pct, long used, long total) {}
    private record SwapInfo(int pct, long used, long total) {}
    private record DiskInfo(int pct, long used, long total) {}

    private MemInfo parseMem(String out) {
        if (out == null) return null;
        for (String line : out.split("\n")) {
            String t = line.trim();
            if (t.startsWith("Mem:")) {
                String[] p = t.split("\\s+");
                if (p.length >= 7) {
                    try {
                        long total = Long.parseLong(p[1]);
                        long available = Long.parseLong(p[6]);
                        long used = total - available;
                        int pct = (int) Math.min(100, Math.max(0, used * 100L / total));
                        return new MemInfo(pct, used, total);
                    } catch (NumberFormatException ignored) {}
                }
                break;
            }
        }
        return null;
    }

    private SwapInfo parseSwap(String out) {
        if (out == null) return null;
        for (String line : out.split("\n")) {
            String t = line.trim();
            if (t.startsWith("Swap:")) {
                String[] p = t.split("\\s+");
                if (p.length >= 4) {
                    try {
                        long total = Long.parseLong(p[1]);
                        long used = Long.parseLong(p[2]);
                        int pct = total > 0 ? (int) Math.min(100, Math.max(0, used * 100L / total)) : 0;
                        return new SwapInfo(pct, used, total);
                    } catch (NumberFormatException ignored) {}
                }
                break;
            }
        }
        return null;
    }

    private DiskInfo parseDisk(String out) {
        if (out == null) return null;
        for (String line : out.split("\n")) {
            String t = line.trim();
            if (t.startsWith("Filesystem")) continue;
            String[] p = t.split("\\s+");
            if (p.length >= 5) {
                try {
                    long total = Long.parseLong(p[1]);
                    long used = Long.parseLong(p[2]);
                    int pct = (int) Math.min(100, Math.max(0, used * 100L / total));
                    return new DiskInfo(pct, used, total);
                } catch (NumberFormatException ignored) {}
            }
        }
        return null;
    }

    // ---- Network ------------------------------------------------------------

    private record NetInfo(long rx, long tx, long rxTotal, long txTotal) {}

    private NetInfo parseNet(String out) {
        if (out == null) return null;
        long rxTotal = 0, txTotal = 0;
        boolean found = false;
        for (String line : out.split("\n")) {
            String t = line.trim();
            // Skip header lines and loopback
            if (t.startsWith("Inter") || t.startsWith("face") || t.contains("lo:"))
                continue;
            // Format: iface: RXbytes ... TXbytes ...
            int colon = t.indexOf(':');
            if (colon < 0) continue;
            String[] parts = t.substring(colon + 1).trim().split("\\s+");
            if (parts.length < 10) continue;
            try {
                rxTotal += Long.parseLong(parts[0]);
                txTotal += Long.parseLong(parts[8]);
                found = true;
            } catch (NumberFormatException ignored) {}
        }
        if (!found) return null;

        long now = System.currentTimeMillis();
        long rxSpeed = 0, txSpeed = 0;
        if (prevNetTime > 0 && now > prevNetTime) {
            double elapsed = (now - prevNetTime) / 1000.0;
            rxSpeed = (long) ((rxTotal - prevNetRx) / elapsed);
            txSpeed = (long) ((txTotal - prevNetTx) / elapsed);
        }
        prevNetRx = rxTotal;
        prevNetTx = txTotal;
        prevNetTime = now;

        return new NetInfo(rxSpeed, txSpeed, rxTotal, txTotal);
    }

    // ---- Load average -------------------------------------------------------

    private record LoadInfo(double load1, double load5, double load15, String procs) {}

    private LoadInfo parseLoad(String raw) {
        if (raw == null) return null;
        String[] parts = raw.trim().split("\\s+");
        if (parts.length < 4) return null;
        try {
            double l1 = Double.parseDouble(parts[0]);
            double l5 = Double.parseDouble(parts[1]);
            double l15 = Double.parseDouble(parts[2]);
            String procs = parts[3]; // e.g. "2/456"
            return new LoadInfo(l1, l5, l15, procs);
        } catch (NumberFormatException e) { return null; }
    }

    // ---- formatting -------------------------------------------------------

    private static String formatUptime(long seconds) {
        long days = seconds / 86400;
        long hours = (seconds % 86400) / 3600;
        if (days > 0) return days + "天 " + hours + "时";
        long minutes = (seconds % 3600) / 60;
        return hours + "时 " + minutes + "分";
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024L) return bytes + " B";
        if (bytes < 1024L * 1024) return String.format("%.0f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / 1048576.0);
        return String.format("%.1f GB", bytes / 1073741824.0);
    }

    /** Format a byte-per-second speed value. */
    private static String formatSpeed(long bytesPerSec) {
        if (bytesPerSec < 0) bytesPerSec = 0;
        if (bytesPerSec < 1024L) return bytesPerSec + "B/s";
        if (bytesPerSec < 1024L * 1024) return String.format("%.0fK/s", bytesPerSec / 1024.0);
        return String.format("%.1fM/s", bytesPerSec / 1048576.0);
    }
}
