package com.platinumcoin.pix.bacen.api;

import com.platinumcoin.pix.bacen.spi.Settlement;
import com.platinumcoin.pix.bacen.spi.SettlementRejectedException;
import com.platinumcoin.pix.bacen.spi.SettlementStatus;
import com.platinumcoin.pix.bacen.spi.SettlementStore;
import com.platinumcoin.pix.bacen.spi.SpiBehavior;
import com.platinumcoin.pix.bacen.spi.SpiDirectory;
import com.platinumcoin.pix.bacen.spi.SpiTimeoutException;
import com.platinumcoin.pix.bacen.spi.SpiUnavailableException;
import jakarta.validation.Valid;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The settlement half of the SPI stub: {@code POST /spi/settlements} to move one Pix over the rail, and
 * {@code GET /spi/settlements/{endToEndId}} to ask what became of it.
 *
 * <h2>The order of the three decisions is the design</h2>
 * <ol>
 *   <li><b>Timeout roll first.</b> A rolled timeout <i>settles and then withholds the answer</i>, so the
 *       decision has to be taken before the hang — that is the whole point of the injection (see
 *       {@link SpiTimeoutException}). The configured latency is not slept as well: the hang subsumes it.</li>
 *   <li><b>Then the latency.</b> Wall-clock time burned before any answer, exactly where a remote rail
 *       burns it. Slept outside the store so a 2s wait never holds a lock other {@code endToEndId}s need.</li>
 *   <li><b>Then the failure roll — before the idempotency lookup, on purpose.</b> An injected {@code 503}
 *       models the <i>transport</i> failing, and a broken transport does not know whether the request it
 *       dropped had already been settled. So a retry can be refused even for an id that is already
 *       {@code SETTLED}, and the caller's escape is to <i>ask</i>
 *       ({@code GET /spi/settlements/{endToEndId}}) rather than to keep posting. Nothing is recorded.</li>
 * </ol>
 *
 * <h2>Idempotency by endToEndId</h2>
 * The first terminal outcome for an id wins forever, and a replay is <b>indistinguishable</b> from the
 * original response — same status, same amount, same {@code recordedAt}. That indistinguishability is
 * what makes "retry after a timeout" safe rather than merely likely to work: the caller needs no special
 * case for "maybe it already happened". A retry that arrives with a <i>different amount</i> for the same
 * id does not overwrite anything either; the recorded amount is replayed and the mismatch is logged loudly,
 * because in a real rail that combination means a caller is reusing an id it should not.
 *
 * <p>Money stays integer cents at this edge in both directions — {@code amountCents} in, {@code amountCents}
 * out, no decimal string anywhere.
 */
@RestController
@RequestMapping("/spi/settlements")
public class SpiSettlementController {

    private static final Logger log = LoggerFactory.getLogger(SpiSettlementController.class);

    /** Recorded on a refusal so the caller (and step 33's reversal) can act on the reason, not a guess. */
    static final String REJECTION_UNKNOWN_CREDITOR = "CREDITOR_KEY_NOT_IN_DICT";

    /**
     * The reason stamped when the admin reject-key knob refuses a settlement (step 35). Distinct from
     * {@code CREDITOR_KEY_NOT_IN_DICT} on purpose: a key on this list <i>is</i> in the DICT (it resolved
     * at send time), it is being refused at settlement to make step 33's reversal reachable end-to-end.
     */
    static final String REJECTION_BY_ADMIN = "SETTLEMENT_REJECTED_BY_ADMIN";

    private final SettlementStore store;
    private final SpiDirectory directory;
    private final SpiBehavior behavior;
    private final Clock clock;

    public SpiSettlementController(SettlementStore store, SpiDirectory directory, SpiBehavior behavior,
            Clock clock) {
        this.store = store;
        this.directory = directory;
        this.behavior = behavior;
        this.clock = clock;
    }

    @PostMapping
    public SettlementView settle(@Valid @RequestBody SettlementRequest request) {
        SpiBehavior.Snapshot dial = behavior.current();
        log.info("A participant asked the SPI to settle a Pix | endToEndId={} creditorKey={} "
                        + "amountCents={} debtorIspb={} latencyMs={} failureRate={} timeoutRate={}",
                request.endToEndId(), request.creditorKey(), request.amountCents(), request.debtorIspb(),
                dial.latencyMs(), dial.failureRate(), dial.timeoutRate());

        if (behavior.rollTimeout(dial)) {
            SettlementStore.Registration registration = decide(request);
            log.warn("Timeout injection fired: the settlement was DECIDED and the answer will be withheld "
                            + "past the caller's timeout, so the caller will believe nothing happened "
                            + "| endToEndId={} status={} amountCents={} replayed={} hangMs={}",
                    request.endToEndId(), registration.settlement().status(),
                    registration.settlement().amountCents(), registration.replayed(),
                    behavior.timeoutHangMs());
            behavior.sleep(behavior.timeoutHangMs());
            throw new SpiTimeoutException("The SPI did not answer within the expected time.");
        }

        behavior.sleep(dial.latencyMs());

        if (behavior.rollFailure(dial)) {
            // Transient by construction: nothing is recorded, so this very endToEndId can still settle on
            // a later attempt. That is what makes step 32's retry-until-success drill meaningful.
            log.warn("Failure injection fired: refusing this attempt as unavailable and recording NOTHING, "
                            + "so the same endToEndId can still settle on a retry "
                            + "| endToEndId={} failureRate={}", request.endToEndId(), dial.failureRate());
            throw new SpiUnavailableException("The SPI is unavailable, try again.");
        }

        SettlementStore.Registration registration = decide(request);
        Settlement settlement = registration.settlement();

        if (registration.replayed() && settlement.amountCents() != request.amountCents()) {
            // Same id, different money: the recorded settlement wins and the caller is told about the one
            // it asked for nowhere. Loud because in a real rail this means an endToEndId is being reused.
            log.warn("A retry reused an endToEndId with a DIFFERENT amount, replaying the amount the SPI "
                            + "actually settled and ignoring the one just sent "
                            + "| endToEndId={} settledAmountCents={} requestedAmountCents={}",
                    request.endToEndId(), settlement.amountCents(), request.amountCents());
        }

        if (settlement.status() == SettlementStatus.FAILED) {
            throw new SettlementRejectedException(settlement);
        }

        log.info("Settlement is terminal at the SPI, answering the participant | endToEndId={} status={} "
                        + "amountCents={} creditorKey={} creditorIspb={} recordedAt={} replayed={}",
                settlement.endToEndId(), settlement.status(), settlement.amountCents(),
                settlement.creditorKey(), settlement.creditorIspb(), settlement.recordedAt(),
                registration.replayed());
        return SettlementView.of(settlement);
    }

    /**
     * The status lookup reconciliation lives on (ADR-0003) — and the one step 32 must call <b>before</b>
     * retrying a settlement that timed out.
     *
     * <p><b>Always {@code 200}, never {@code 404}.</b> "I have never heard of this id" is an answer the
     * caller has to be able to act on, and a {@code 404} would be indistinguishable from a typo in the
     * URL or a stub that is not the SPI at all. So the absence of a record is reported <i>in the body</i>
     * as {@link SettlementStatus#UNKNOWN}.
     */
    @GetMapping("/{endToEndId}")
    public SettlementView status(@PathVariable("endToEndId") String endToEndId) {
        SettlementView view = store.find(endToEndId)
                .map(SettlementView::of)
                .orElseGet(() -> SettlementView.unknown(endToEndId));
        log.info("A participant queried the SPI for the fate of a settlement | endToEndId={} status={} "
                        + "amountCents={} recordedAt={}",
                endToEndId, view.status(), view.amountCents(), view.recordedAt());
        return view;
    }

    /**
     * Decide the terminal outcome for a <i>new</i> id, or hand back the one already recorded. The creditor
     * key is validated against the SPI's own DICT: a key no participant answers for is refused
     * permanently, which is what gives {@link SettlementStatus#FAILED} a real producer instead of an
     * aspirational enum value (step 33 reverses on it).
     */
    private SettlementStore.Registration decide(SettlementRequest request) {
        return store.register(request.endToEndId(), () -> {
            Instant now = clock.instant();
            // The admin reject knob (step 35) is checked BEFORE the DICT lookup: a key on the reject list
            // is refused at settlement even though the DICT resolves it, which is the whole point — a real
            // send to a DICT-known key can be driven to step 33's compensating reversal against the compose
            // stack. Recorded as a terminal FAILED, so a later GET reports it and reconciliation reverses.
            if (behavior.shouldReject(request.creditorKey())) {
                log.warn("Creditor key is on the admin reject-list, refusing this settlement permanently "
                                + "even though the DICT knows it — the payer must be made whole by a "
                                + "compensating posting | endToEndId={} creditorKey={} reason={}",
                        request.endToEndId(), request.creditorKey(), REJECTION_BY_ADMIN);
                return Settlement.rejected(request.endToEndId(), request.amountCents(),
                        request.creditorKey(), REJECTION_BY_ADMIN, now);
            }
            return directory.lookup(request.creditorKey())
                    .map(entry -> {
                        log.info("Creditor key answers to a participant in the DICT, settling "
                                        + "| endToEndId={} creditorKey={} creditorIspb={} participant={}",
                                request.endToEndId(), request.creditorKey(), entry.ispb(),
                                entry.participant());
                        return Settlement.settled(request.endToEndId(), request.amountCents(),
                                request.creditorKey(), entry.ispb(), now);
                    })
                    .orElseGet(() -> {
                        log.warn("Creditor key answers to no participant in the DICT, refusing the "
                                        + "settlement permanently, the payer must be made whole by a "
                                        + "compensating posting | endToEndId={} creditorKey={} reason={}",
                                request.endToEndId(), request.creditorKey(), REJECTION_UNKNOWN_CREDITOR);
                        return Settlement.rejected(request.endToEndId(), request.amountCents(),
                                request.creditorKey(), REJECTION_UNKNOWN_CREDITOR, now);
                    });
        });
    }
}
