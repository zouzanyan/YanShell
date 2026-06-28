package com.yanshell.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Cross-platform helpers for finding and killing local processes that
 * are bound to a given TCP/UDP port.
 *
 * <p>Windows path uses {@code netstat -ano} + {@code tasklist}. Linux/macOS
 * path uses {@code lsof -nP -iTCP:<port> -iUDP:<port>}. Either way the
 * returned rows are best-effort — if the OS doesn't expose ownership for
 * a socket (e.g. owned by another user) the row is simply skipped.</p>
 */
public final class LocalProcessUtil {

    private static final Logger log = LoggerFactory.getLogger(LocalProcessUtil.class);

    private LocalProcessUtil() {}

    /** A single (pid, name, protocol, localAddress, state) row. */
    public record PortProcess(int pid,
                              String name,
                              String protocol,
                              String localAddress,
                              String state) { }

    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /**
     * Find all processes listening on (or otherwise bound to) {@code port}.
     * Returns an empty list if nothing is found or the lookup fails.
     */
    public static List<PortProcess> findByPort(int port) {
        if (port <= 0 || port > 65535) return List.of();
        try {
            return isWindows() ? findWindows(port) : findUnix(port);
        } catch (Exception e) {
            log.warn("findByPort({}) failed: {}", port, e.getMessage());
            return List.of();
        }
    }

    /**
     * Kill the given PID. {@code force} maps to {@code /F} on Windows and
     * {@code -9} on Unix. Returns {@code true} only if the process exited
     * with code 0.
     */
    public static boolean kill(int pid, boolean force) {
        if (pid <= 0) return false;
        try {
            ProcessBuilder pb = isWindows()
                    ? new ProcessBuilder("taskkill", force ? "/F" : "/T", "/PID", String.valueOf(pid))
                    : new ProcessBuilder("kill", force ? "-9" : "-15", String.valueOf(pid));
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String out = readAll(p);
            int code = p.waitFor();
            if (code != 0) {
                log.info("kill pid={} force={} rc={} out={}", pid, force, code, out.trim());
            }
            return code == 0;
        } catch (IOException | InterruptedException e) {
            log.warn("kill({}) failed: {}", pid, e.getMessage());
            return false;
        }
    }

    // ---- Windows --------------------------------------------------------

    private static List<PortProcess> findWindows(int port) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder("netstat", "-ano");
        pb.redirectErrorStream(true);
        Process p = pb.start();
        // Chinese Windows defaults to GBK; use platform charset so 监听/LISTENING parses cleanly.
        List<String> lines = readLines(p, Charset.defaultCharset());
        p.waitFor();

        List<PortProcess> rows = new ArrayList<>();
        // Aggregate pids first so we batch a single tasklist call per pid.
        Map<Integer, List<String[]>> byPid = new LinkedHashMap<>();
        String portSuffix = ":" + port;
        for (String line : lines) {
            String s = line.trim();
            if (s.isEmpty()) continue;
            // Columns: Proto  LocalAddress  ForeignAddress  State  PID    (UDP has no State)
            String[] cols = s.split("\\s+");
            if (cols.length < 4) continue;
            String proto = cols[0];
            if (!"TCP".equalsIgnoreCase(proto) && !"UDP".equalsIgnoreCase(proto)) continue;
            String local = cols[1];
            if (!matchesPort(local, portSuffix)) continue;
            int pid;
            String state;
            try {
                if ("TCP".equalsIgnoreCase(proto) && cols.length >= 5) {
                    state = cols[3];
                    pid = Integer.parseInt(cols[4]);
                } else {
                    state = "";
                    pid = Integer.parseInt(cols[cols.length - 1]);
                }
            } catch (NumberFormatException ex) {
                continue;
            }
            byPid.computeIfAbsent(pid, k -> new ArrayList<>())
                 .add(new String[]{proto, local, state});
        }

        for (var entry : byPid.entrySet()) {
            int pid = entry.getKey();
            String name = lookupProcessNameWindows(pid);
            for (String[] r : entry.getValue()) {
                rows.add(new PortProcess(pid, name, r[0], r[1], r[2]));
            }
        }
        return rows;
    }

    /** Return whether netstat's "Local Address" column ends with {@code :<port>}. */
    private static boolean matchesPort(String localAddress, String portSuffix) {
        int colon = localAddress.lastIndexOf(':');
        if (colon < 0) return false;
        return localAddress.substring(colon).equals(portSuffix);
    }

    private static String lookupProcessNameWindows(int pid) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "tasklist", "/FI", "PID eq " + pid, "/FO", "CSV", "/NH");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            List<String> lines = readLines(p, Charset.defaultCharset());
            p.waitFor();
            for (String line : lines) {
                // CSV row: "name.exe","1234","Console","1","12,345 K"
                String s = line.trim();
                if (s.isEmpty() || !s.startsWith("\"")) continue;
                int end = s.indexOf('"', 1);
                if (end > 1) return s.substring(1, end);
            }
        } catch (IOException | InterruptedException ignored) {
        }
        return "(unknown)";
    }

    // ---- Unix / macOS ---------------------------------------------------

    private static List<PortProcess> findUnix(int port) throws IOException, InterruptedException {
        // lsof is the most portable and gives us name, pid, protocol, address,
        // and (for TCP) the listening state in one pass.
        ProcessBuilder pb = new ProcessBuilder(
                "lsof", "-nP", "-iTCP:" + port, "-iUDP:" + port);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        List<String> lines = readLines(p, Charset.defaultCharset());
        p.waitFor();

        List<PortProcess> rows = new ArrayList<>();
        boolean header = true;
        for (String line : lines) {
            if (header) { header = false; continue; }
            String s = line.trim();
            if (s.isEmpty()) continue;
            // COMMAND PID USER FD TYPE DEVICE SIZE/OFF NODE NAME
            String[] cols = s.split("\\s+");
            if (cols.length < 9) continue;
            int pid;
            try {
                pid = Integer.parseInt(cols[1]);
            } catch (NumberFormatException ex) {
                continue;
            }
            String name = cols[0];
            String proto = cols[7]; // TCP / UDP
            String nameField = cols[8]; // 127.0.0.1:8080 (LISTEN)
            String state = "";
            if (cols.length >= 10 && cols[9].startsWith("(") && cols[9].endsWith(")")) {
                state = cols[9].substring(1, cols[9].length() - 1);
            }
            rows.add(new PortProcess(pid, name, proto, nameField, state));
        }
        return rows;
    }

    // ---- io helpers -----------------------------------------------------

    private static List<String> readLines(Process p, Charset cs) throws IOException {
        List<String> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), cs))) {
            String line;
            while ((line = r.readLine()) != null) out.add(line);
        }
        return out;
    }

    private static String readAll(Process p) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(p.getInputStream(), Charset.defaultCharset()))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
        }
        return sb.toString();
    }
}
