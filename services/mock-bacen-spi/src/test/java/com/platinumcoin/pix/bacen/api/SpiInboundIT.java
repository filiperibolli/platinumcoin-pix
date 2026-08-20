package com.platinumcoin.pix.bacen.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.platinumcoin.pix.bacen.spi.InboundWebhookClient;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * HTTP behaviour of the inbound simulation (step 37) — the endToEndId the rail mints and the decimal
 * amount it converts to cents. The delivery itself is stubbed: the client's retry/refusal semantics are
 * pinned separately against a real socket in {@code InboundWebhookClientTest}, and what this test is
 * about is the <b>generation</b> — the half of the originating side that has to be right before anything
 * is delivered at all.
 *
 * <p>No LocalStack, no Testcontainers: the stub touches no AWS service. Named {@code *IT} because it
 * drives the whole wired application through HTTP.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(SpiInboundIT.StubDelivery.class)
class SpiInboundIT {

    @Autowired
    MockMvc mvc;

    @Test
    void mintsAnEndToEndIdStampedWithThePayersIspbAndConvertsTheAmountToCents() throws Exception {
        mvc.perform(post("/simulate/inbound-pix")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"pixKey":"bob@platinum.com","amount":"300.00",
                                 "payerName":"External Payer"}"""))
                .andExpect(status().isOk())
                // BACEN's shape, 32 chars — and the ISPB is the PAYER's (99999999, Banco OtherBank),
                // never PlatinumCoin's: an end-to-end id names the participant that originated the money.
                .andExpect(jsonPath("$.endToEndId", Matchers.matchesPattern("^E99999999\\d{12}[A-Za-z0-9]{11}$")))
                .andExpect(jsonPath("$.amountCents").value(30000))
                .andExpect(jsonPath("$.payerIspb").value("99999999"))
                .andExpect(jsonPath("$.outcome").value("CREDITED"))
                .andExpect(jsonPath("$.deliveryAttempts").value(1));
    }

    /** Every call mints a fresh id — two simulated payments are two payments, never one deduped twice. */
    @Test
    void mintsAFreshEndToEndIdPerSimulation() throws Exception {
        String first = simulateAndReadId();
        String second = simulateAndReadId();
        org.assertj.core.api.Assertions.assertThat(first).isNotEqualTo(second);
    }

    /** The stub validates money as strictly as the real edge: "0.00" is not a payment. */
    @Test
    void refusesANonPositiveAmount() throws Exception {
        mvc.perform(post("/simulate/inbound-pix")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pixKey\":\"bob@platinum.com\",\"amount\":\"0.00\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    /** Sub-cent precision is refused rather than rounded — nobody's money is ever rounded silently. */
    @Test
    void refusesSubCentPrecision() throws Exception {
        mvc.perform(post("/simulate/inbound-pix")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pixKey\":\"bob@platinum.com\",\"amount\":\"1.005\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void refusesAMalformedPayerIspb() throws Exception {
        mvc.perform(post("/simulate/inbound-pix")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"pixKey":"bob@platinum.com","amount":"10.00","payerIspb":"123"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    private String simulateAndReadId() throws Exception {
        return com.jayway.jsonpath.JsonPath.read(
                mvc.perform(post("/simulate/inbound-pix")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"pixKey\":\"bob@platinum.com\",\"amount\":\"1.00\"}"))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString(),
                "$.endToEndId");
    }

    /**
     * The participant, stubbed: it always accepts on the first presentation. {@code @Primary} rather than
     * a bean override, so the real client stays in the context and a wiring mistake in it still fails
     * these tests at startup.
     */
    @TestConfiguration
    static class StubDelivery {

        @Bean
        @Primary
        InboundWebhookClient stubInboundWebhookClient() {
            return new InboundWebhookClient(
                    org.springframework.web.client.RestClient.builder(),
                    "http://localhost:1", "stub-token", 1, 0, 50, 50) {
                @Override
                public DeliveryReceipt deliver(String endToEndId, String pixKey, long amountCents,
                        String payerName, String payerIspb) {
                    return new DeliveryReceipt("in-" + endToEndId, "CREDITED", 1);
                }
            };
        }
    }
}
