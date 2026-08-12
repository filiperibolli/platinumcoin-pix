package com.platinumcoin.pix.bacen.spi;

/**
 * One entry of BACEN's <b>DICT</b> as this stub knows it: a Pix key held at another participant.
 *
 * @param ispb        the participant's 8-digit ISPB — the identifier that actually routes the money,
 *                    and the value account-service surfaces as {@code externalBank}
 * @param participant the human name of the holding institution (logs and demos only; nothing routes on it)
 * @param keyType     {@code EMAIL} / {@code CPF} / {@code PHONE} / {@code EVP} — the DICT's own view of
 *                    the key's kind, which the caller may or may not recognise (see
 *                    {@code HttpExternalDirectory} in account-service: an unrecognised kind must not
 *                    break a resolution, since a foreign directory's vocabulary is not ours to enforce)
 */
public record DictEntry(String ispb, String participant, String keyType) {
}
