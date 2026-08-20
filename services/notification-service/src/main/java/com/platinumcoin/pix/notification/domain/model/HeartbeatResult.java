package com.platinumcoin.pix.notification.domain.model;

/**
 * What one heartbeat sweep did: how many open streams were pinged, and how many turned out to be dead
 * and were removed.
 *
 * <p><b>Why {@code evicted} is worth reporting.</b> A dropped SSE connection is frequently invisible to
 * the server — the client is gone, no FIN arrives, and the emitter stays in the registry believing it
 * has a reader. The heartbeat write is what finally fails, which makes this sweep the platform's real
 * garbage collector for connections; a registry that only ever grows is how a long-lived-connection
 * service runs out of memory.
 */
public record HeartbeatResult(int pinged, int evicted) {
}
