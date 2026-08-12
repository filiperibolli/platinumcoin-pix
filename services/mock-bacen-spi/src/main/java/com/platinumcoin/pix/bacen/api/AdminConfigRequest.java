package com.platinumcoin.pix.bacen.api;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * A <b>partial</b> update of the SPI dial. Every field is boxed and nullable, and an absent field leaves
 * that knob exactly where it was — which is why {@code -d '{"failureRate":1.0}'} arms one drill without
 * silently resetting a latency the runbook set two commands earlier. Bean Validation skips {@code null},
 * so nullability and "leave unchanged" are the same thing here rather than two competing conventions.
 *
 * <p>The ranges are enforced on the wire: {@code latencyMs} is capped at the real SPI's 10-second SLA
 * (a stub allowed to be slower than the thing it stands in for would let a test pass against a fiction),
 * and the two rates are probabilities in {@code [0,1]}. Out of range ⇒ {@code 400 VALIDATION_ERROR} from
 * common-lib's shared handler, so the drill fails loudly instead of arming something meaningless.
 */
public record AdminConfigRequest(
        @Min(value = 0, message = "latencyMs must be >= 0")
        @Max(value = 10_000, message = "latencyMs must be <= 10000 (the real SPI SLA)")
        Long latencyMs,

        @DecimalMin(value = "0.0", message = "failureRate must be >= 0.0")
        @DecimalMax(value = "1.0", message = "failureRate must be <= 1.0")
        Double failureRate,

        @DecimalMin(value = "0.0", message = "timeoutRate must be >= 0.0")
        @DecimalMax(value = "1.0", message = "timeoutRate must be <= 1.0")
        Double timeoutRate) {
}
