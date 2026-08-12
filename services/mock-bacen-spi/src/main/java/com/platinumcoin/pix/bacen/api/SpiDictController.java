package com.platinumcoin.pix.bacen.api;

import com.platinumcoin.pix.bacen.spi.DictKeyNotFoundException;
import com.platinumcoin.pix.bacen.spi.SpiDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /spi/dict/{key}} — BACEN's <b>DICT</b>: which participant holds this Pix key. This endpoint
 * is what closes the seam account-service left open in step 11: a key PlatinumCoin does not hold locally
 * is no longer simply "not found", it is asked about here, and an answer turns the send into its external
 * branch (debit to clearing, settle asynchronously).
 *
 * <p><b>Deliberately outside the failure injection.</b> Unlike settlement, this call sits on the
 * <i>synchronous</i> send path with the payer waiting on it, so latency/failure/timeout knobs do not apply
 * (see {@link SpiDirectory}). What happens when the directory is <i>unreachable</i> is therefore decided on
 * the caller's side — account-service answers {@code 503 DIRECTORY_UNAVAILABLE} rather than pretending the
 * key does not exist, because "I cannot ask" and "it does not exist" are different facts and only one of
 * them is worth telling a payer.
 *
 * <p>The key travels as a path variable containing {@code @}, {@code .} or {@code +} — all legal in a path
 * segment, and Spring Boot 3 no longer truncates suffixes, so {@code bob@otherbank.com} arrives intact.
 */
@RestController
public class SpiDictController {

    private static final Logger log = LoggerFactory.getLogger(SpiDictController.class);

    private final SpiDirectory directory;

    public SpiDictController(SpiDirectory directory) {
        this.directory = directory;
    }

    @GetMapping("/spi/dict/{key}")
    public DictEntryResponse resolve(@PathVariable("key") String key) {
        String normalized = SpiDirectory.normalize(key);
        log.info("A participant asked the DICT which institution holds a Pix key "
                + "| keyValue={} normalizedValue={}", key, normalized);

        return directory.lookup(key)
                .map(entry -> {
                    log.info("DICT resolved the key to a participant | keyValue={} normalizedValue={} "
                                    + "ispb={} participant={} keyType={}",
                            key, normalized, entry.ispb(), entry.participant(), entry.keyType());
                    return DictEntryResponse.of(normalized, entry);
                })
                .orElseThrow(() -> {
                    // A lookup miss is an ordinary answer, not a fault: INFO keeps the correlationId trace
                    // complete without pretending something broke.
                    log.info("DICT holds no participant for this key, answering 404 so the caller can tell "
                                    + "the payer the key does not exist | keyValue={} normalizedValue={}",
                            key, normalized);
                    return new DictKeyNotFoundException("No participant holds the Pix key " + normalized);
                });
    }
}
