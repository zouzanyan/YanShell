package com.yanshell.ssh;

import org.apache.sshd.client.keyverifier.ServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.SocketAddress;
import java.security.PublicKey;

/**
 * SSH host key verifier that stores known keys in a local file,
 * similar to OpenSSH's {@code known_hosts} mechanism.
 *
 * <p>Behaviour:</p>
 * <ul>
 *   <li><b>Unknown host</b> — asks the user to confirm the fingerprint via
 *       {@link HostKeyPrompt}. If accepted, saves to known_hosts.</li>
 *   <li><b>Known host, matching key</b> — accepts silently.</li>
 *   <li><b>Known host, changed key</b> — warns the user that the key has
 *       changed (possible MITM) and asks whether to accept the new key.</li>
 * </ul>
 *
 * <p>The prompt callback is invoked from the SSH I/O thread. It is the
 * callback's responsibility to marshal UI work onto the EDT if needed.</p>
 */
public final class KnownHostsVerifier implements ServerKeyVerifier {

    private static final Logger log = LoggerFactory.getLogger(KnownHostsVerifier.class);

    /** Callback for asking the user about unknown / changed host keys. */
    @FunctionalInterface
    public interface HostKeyPrompt {
        /**
         * @param host       hostname
         * @param port       port
         * @param fingerprint SHA256:xxx fingerprint
         * @param changed    true if key changed (MITM warning), false if new host
         * @return true to accept, false to reject
         */
        boolean ask(String host, int port, String fingerprint, boolean changed);
    }

    private final KnownHostsStore store;
    private final HostKeyPrompt prompt;

    public KnownHostsVerifier(HostKeyPrompt prompt) {
        this(new KnownHostsStore(), prompt);
    }

    KnownHostsVerifier(KnownHostsStore store, HostKeyPrompt prompt) {
        this.store = store;
        this.prompt = prompt;
    }

    @Override
    public boolean verifyServerKey(ClientSession clientSession,
                                   SocketAddress remoteAddress,
                                   PublicKey serverKey) {
        String host = resolveHost(remoteAddress);
        int port = resolvePort(remoteAddress);
        String fp = KnownHostsStore.fingerprint(serverKey);

        KnownHostsStore.Status status = store.check(host, port, serverKey);

        switch (status) {
            case MATCH -> {
                log.debug("Host key for {}:{} matches known_hosts", host, port);
                return true;
            }
            case UNKNOWN -> {
                log.info("Unknown host {}:{}, fingerprint={}", host, port, fp);
                boolean accept = prompt.ask(host, port, fp, false);
                if (accept) {
                    store.add(host, port, serverKey);
                    log.info("Accepted and saved host key for {}:{}", host, port);
                }
                return accept;
            }
            case CHANGED -> {
                log.warn("Host key CHANGED for {}:{} — possible MITM attack! fingerprint={}",
                        host, port, fp);
                boolean accept = prompt.ask(host, port, fp, true);
                if (accept) {
                    store.add(host, port, serverKey);
                    log.info("Accepted changed host key for {}:{}", host, port);
                }
                return accept;
            }
            default -> {
                return false;
            }
        }
    }

    private static String resolveHost(SocketAddress addr) {
        // addr.toString() returns "hostname/192.168.1.1:22" or "/192.168.1.1:22"
        String s = addr.toString();
        int slash = s.indexOf('/');
        if (slash >= 0) {
            String hostPart = s.substring(0, slash);
            if (!hostPart.isEmpty()) return hostPart;
            String rest = s.substring(slash + 1);
            int colon = rest.lastIndexOf(':');
            return colon > 0 ? rest.substring(0, colon) : rest;
        }
        return s;
    }

    private static int resolvePort(SocketAddress addr) {
        String s = addr.toString();
        int colon = s.lastIndexOf(':');
        if (colon > 0) {
            try {
                return Integer.parseInt(s.substring(colon + 1));
            } catch (NumberFormatException ignored) {}
        }
        return 22;
    }
}
