package com.platinumcoin.pix.account.domain.model;

/**
 * What BACEN's DICT knows about a key held at another participant.
 *
 * @param ispb        the holder's 8-digit ISPB — the only field that matters to the send flow, because it
 *                    is what identifies the destination participant. Surfaced to callers as
 *                    {@code externalBank} in {@link KeyResolution}.
 * @param participant the institution's name; logs and demos only, nothing routes on it
 * @param keyType     the DICT's view of the key's kind, <b>nullable</b>. A foreign registry's vocabulary is
 *                    not ours to enforce: a kind we do not recognise must not sink an otherwise perfectly
 *                    good resolution, so the adapter maps an unknown value to {@code null} and the send
 *                    proceeds on the ISPB, which is the part that actually moves money.
 */
public record ExternalDirectoryEntry(String ispb, String participant, PixKeyType keyType) {
}
