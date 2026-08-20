package com.platinumcoin.pix.settlement.api;

import com.platinumcoin.pix.settlement.domain.usecase.ReceiveInboundOutcome;
import com.platinumcoin.pix.settlement.domain.usecase.ReceiveInboundPixUseCase;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * settlement-service's <b>first and only</b> HTTP endpoint: the webhook BACEN calls to deliver a Pix to
 * one of our customers (step 37, ARCHITECTURE §6.8). Until now this service was driven entirely by a
 * queue.
 *
 * <h2>Why a webhook and not a queue</h2>
 * A queue in front of this would decouple us from the rail's timing — and would also mean answering
 * "accepted" to BACEN before knowing whether the key resolves or the ledger is up, i.e. acknowledging a
 * payment we might then be unable to credit. Handling it synchronously and idempotently lets the answer
 * carry the truth: {@code 200} means credited, {@code 422} means bounce it, {@code 503} means ask again.
 * A buffering queue is a documented production evolution, not local infra (step 36).
 *
 * <h2>Authentication: JWT-exempt, but never anonymous</h2>
 * The route is on {@code jwt.public-paths} because no user is calling it and BACEN holds no PlatinumCoin
 * token (ADR-0007 — a real participant authenticates with mTLS and an ICP-Brasil certificate, a whole
 * trust domain away from our HS256 tokens). But it <b>credits money</b>, so it is guarded by the shared
 * {@code SPI_WEBHOOK_TOKEN}: without it, any process that can reach port 8086 could mint spendable balance
 * (threat model, boundary B4; production posture is mTLS + BACEN message signing).
 *
 * <p>The header is read here and <b>checked in the use case</b>, before anything else it does. A
 * controller binds and delegates; it holds no policy (ADR-0011), and "may this call move money" is the
 * most policy-shaped decision in the service. Putting it in a filter would hide it from the plain-Java
 * test that pins the ordering — that a forged call resolves nothing and posts nothing.
 */
@RestController
@RequestMapping("/v1/inbound")
public class InboundPixController {

    /** The shared-secret header BACEN presents. Not {@code Authorization}: it is not a bearer token. */
    static final String WEBHOOK_TOKEN_HEADER = "X-Webhook-Token";

    private final ReceiveInboundPixUseCase receiveInboundPix;

    public InboundPixController(ReceiveInboundPixUseCase receiveInboundPix) {
        this.receiveInboundPix = receiveInboundPix;
    }

    /**
     * Always {@code 200} on a delivery we took — including a redelivery of one we already took. The rail
     * asked "did you accept this payment?", and to an {@code endToEndId} already credited the truthful
     * answer is still yes; an error there would have BACEN re-presenting a payment that <i>was</i>
     * delivered. The three refusals ({@code 401}, {@code 422}, {@code 503}) are mapped by
     * {@link SettlementExceptionHandler}.
     */
    @PostMapping("/pix")
    public InboundPixAck receive(
            @RequestHeader(name = WEBHOOK_TOKEN_HEADER, required = false) String webhookToken,
            @Valid @RequestBody InboundPixRequest request) {
        ReceiveInboundOutcome outcome = receiveInboundPix.execute(request.toCommand(), webhookToken);
        return InboundPixAck.of(request.endToEndId(), outcome);
    }
}
