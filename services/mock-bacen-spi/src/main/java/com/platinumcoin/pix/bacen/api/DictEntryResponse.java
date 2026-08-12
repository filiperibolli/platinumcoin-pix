package com.platinumcoin.pix.bacen.api;

import com.platinumcoin.pix.bacen.spi.DictEntry;

/**
 * The DICT's answer for one key. Adds the queried {@code key} to the stored {@link DictEntry} so the
 * response is self-describing in a log or a demo — the caller asked by path variable and gets back the
 * <i>normalised</i> value the directory actually matched, which is how a casing surprise becomes visible
 * instead of mysterious.
 */
public record DictEntryResponse(String key, String keyType, String ispb, String participant) {

    public static DictEntryResponse of(String normalizedKey, DictEntry entry) {
        return new DictEntryResponse(normalizedKey, entry.keyType(), entry.ispb(), entry.participant());
    }
}
