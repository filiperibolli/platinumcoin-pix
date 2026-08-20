package com.platinumcoin.pix.notification.domain.usecase;

/** What one heartbeat sweep did: streams pinged, and dead ones removed on the way. */
public record HeartbeatOutcome(int pinged, int evicted) {
}
