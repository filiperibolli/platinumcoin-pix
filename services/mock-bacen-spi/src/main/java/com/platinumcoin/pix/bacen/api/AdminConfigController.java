package com.platinumcoin.pix.bacen.api;

import com.platinumcoin.pix.bacen.spi.SpiBehavior;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The drill switch: {@code POST /admin/config} changes how the SPI misbehaves <b>while the stack is
 * running</b>, and {@code GET /admin/config} reports what is currently armed.
 *
 * <p><b>Why runtime and not just environment variables.</b> The failure scenarios this exists for are
 * sequences, not states: send a payment, <i>then</i> break BACEN, watch the retries, un-break it, watch it
 * settle (docs/local-dev.md §5.5). Restarting the container between those steps would reset the SPI's
 * memory of every settlement — destroying the very state the drill is about. A configuration you can only
 * set at boot cannot express "fail the next five attempts".
 *
 * <p><b>Not an endpoint a real SPI has.</b> It is under {@code /admin} rather than {@code /spi} to keep
 * that obvious: nothing in the platform may call it, only a human or a test. In a deployed system this is
 * the kind of surface that would not exist at all; here it is the reason the resilience work is testable.
 */
@RestController
@RequestMapping("/admin/config")
public class AdminConfigController {

    private static final Logger log = LoggerFactory.getLogger(AdminConfigController.class);

    private final SpiBehavior behavior;

    public AdminConfigController(SpiBehavior behavior) {
        this.behavior = behavior;
    }

    @PostMapping
    public AdminConfigResponse update(@Valid @RequestBody AdminConfigRequest request) {
        log.info("An operator asked to re-arm the SPI dial, absent fields are left unchanged "
                        + "| requestedLatencyMs={} requestedFailureRate={} requestedTimeoutRate={}",
                request.latencyMs(), request.failureRate(), request.timeoutRate());
        SpiBehavior.Snapshot updated =
                behavior.update(request.latencyMs(), request.failureRate(), request.timeoutRate());
        return AdminConfigResponse.of(updated, behavior.timeoutHangMs());
    }

    /** What is armed right now — so a confusing test run can be explained instead of guessed at. */
    @GetMapping
    public AdminConfigResponse current() {
        SpiBehavior.Snapshot dial = behavior.current();
        log.info("Reporting the SPI dial currently armed | latencyMs={} failureRate={} timeoutRate={} "
                        + "timeoutHangMs={}",
                dial.latencyMs(), dial.failureRate(), dial.timeoutRate(), behavior.timeoutHangMs());
        return AdminConfigResponse.of(dial, behavior.timeoutHangMs());
    }
}
