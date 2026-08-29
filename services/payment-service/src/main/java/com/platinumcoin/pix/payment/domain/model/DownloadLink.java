package com.platinumcoin.pix.payment.domain.model;

import java.time.Instant;

/**
 * A time-limited way to fetch an export artifact (step 53): the presigned URL and the instant it stops
 * working.
 *
 * <p>Both halves travel together on purpose. A URL without its expiry forces every client to guess how
 * long it has — and to discover it guessed wrong by getting a signature error from object storage
 * instead of an answer from this platform. With {@code expiresAt} on the response, a client that finds
 * it stale simply polls the status endpoint again and is handed a fresh one.
 *
 * @param url       presigned URL; treat as a secret while it lives — it grants the bytes to whoever
 *                  holds it, which is exactly why it is short-lived and never stored
 * @param expiresAt when the signature stops being accepted
 */
public record DownloadLink(String url, Instant expiresAt) {
}
