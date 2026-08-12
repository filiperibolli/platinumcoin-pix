package com.platinumcoin.pix.bacen.config;

import com.platinumcoin.pix.bacen.spi.DictEntry;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Boot-time configuration of the stub, bound from {@code bacen.*} (env vars {@code BACEN_LATENCY_MS},
 * {@code BACEN_FAILURE_RATE}, {@code BACEN_TIMEOUT_RATE}, {@code BACEN_TIMEOUT_HANG_MS} — see
 * {@code docs/local-dev.md} §3). The first three are only the <i>initial</i> values: {@code SpiBehavior}
 * takes ownership of them and {@code POST /admin/config} moves them at runtime.
 *
 * <p>The DICT keys are normalised to lowercase <b>at binding time</b> rather than on every lookup, so a
 * configuration typed as {@code Bob@OtherBank.com} still answers and the hot path stays a plain map hit.
 *
 * @param latencyMs     how long a settlement takes before answering (the real SPI SLA is ≤ 10s)
 * @param failureRate   fraction of settlement calls answered {@code 503}, recording nothing (transient)
 * @param timeoutRate   fraction of settlement calls that settle and then hang past the caller's timeout
 * @param timeoutHangMs how long such a call hangs — must exceed the client's own timeout to mean anything
 * @param dict          the external-PSP keys this stub answers for, keyed by normalised key value
 */
@ConfigurationProperties(prefix = "bacen")
public record BacenProperties(
        long latencyMs,
        double failureRate,
        double timeoutRate,
        long timeoutHangMs,
        Map<String, DictEntry> dict) {

    public BacenProperties {
        dict = dict == null ? Map.of() : dict.entrySet().stream().collect(Collectors.toUnmodifiableMap(
                e -> e.getKey().trim().toLowerCase(Locale.ROOT), Map.Entry::getValue));
    }
}
