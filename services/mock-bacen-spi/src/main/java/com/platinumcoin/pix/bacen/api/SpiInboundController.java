package com.platinumcoin.pix.bacen.api;

import com.platinumcoin.pix.bacen.spi.Amount;
import com.platinumcoin.pix.bacen.spi.InboundPixGenerator;
import com.platinumcoin.pix.bacen.spi.InboundWebhookClient;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The trigger that makes money <b>arrive</b>: {@code POST /simulate/inbound-pix} (step 37).
 *
 * <p><b>Why {@code /simulate/…} and not {@code /spi/…}.</b> Everything under {@code /spi} is a stub of a
 * real BACEN API that PlatinumCoin calls. This one is the opposite direction and has no real counterpart
 * at all — no participant asks BACEN to send it money. It is a <i>test hook</i> on the rail, in the same
 * family as {@code /admin/config}, and naming it apart keeps the honest boundary visible: nothing under
 * {@code /simulate} is pretending to be a real protocol.
 *
 * <p>It performs the two acts the originating side of a Pix performs — mint the {@code endToEndId} and
 * present the payment to the receiving participant, retrying while the outcome is unknown — and then
 * reports what the participant said. The failure injection of {@code /admin/config} deliberately does
 * <b>not</b> apply here: those knobs model <i>BACEN</i> misbehaving toward us on the settlement path, and
 * the interesting failure on this path (a redelivery) is produced by the client's own retry loop.
 */
@RestController
@RequestMapping("/simulate")
public class SpiInboundController {

    private static final Logger log = LoggerFactory.getLogger(SpiInboundController.class);

    private final InboundPixGenerator generator;
    private final InboundWebhookClient webhook;
    private final String defaultPayerIspb;
    private final String defaultPayerName;

    public SpiInboundController(
            InboundPixGenerator generator,
            InboundWebhookClient webhook,
            @Value("${bacen.inbound.default-payer-ispb:99999999}") String defaultPayerIspb,
            @Value("${bacen.inbound.default-payer-name:External Payer}") String defaultPayerName) {
        this.generator = generator;
        this.webhook = webhook;
        this.defaultPayerIspb = defaultPayerIspb;
        this.defaultPayerName = defaultPayerName;
    }

    @PostMapping("/inbound-pix")
    public InboundPixResponse simulateInbound(@Valid @RequestBody InboundPixRequest request) {
        long amountCents = Amount.toCents(request.amount());
        String payerIspb = StringUtils.hasText(request.payerIspb())
                ? request.payerIspb() : defaultPayerIspb;
        String payerName = StringUtils.hasText(request.payerName())
                ? request.payerName() : defaultPayerName;
        String endToEndId = generator.newEndToEndId(payerIspb);

        log.info("Simulating a Pix arriving from another participant, minting its endToEndId and "
                        + "presenting it to the receiving participant | endToEndId={} pixKey={} "
                        + "rawAmount={} amountCents={} payerName={} payerIspb={}",
                endToEndId, request.pixKey(), request.amount(), amountCents, payerName, payerIspb);

        InboundWebhookClient.DeliveryReceipt receipt =
                webhook.deliver(endToEndId, request.pixKey(), amountCents, payerName, payerIspb);

        return InboundPixResponse.of(
                endToEndId, request.pixKey(), amountCents, payerName, payerIspb, receipt);
    }
}
