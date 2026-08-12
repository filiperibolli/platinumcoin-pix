package com.platinumcoin.pix.bacen.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The DICT surface: which participant holds a Pix key. This is the endpoint account-service delegates to
 * for every key it does not hold locally, so its two answers — an ISPB or a {@code 404} — are the entire
 * vocabulary of the external send branch.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SpiDictIT {

    @Autowired
    MockMvc mvc;

    @Test
    void aConfiguredExternalKeyResolvesToItsParticipant() throws Exception {
        mvc.perform(get("/spi/dict/{key}", "bob@otherbank.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key", is("bob@otherbank.com")))
                .andExpect(jsonPath("$.ispb", is("99999999")))
                .andExpect(jsonPath("$.participant", is("Banco OtherBank S.A.")))
                .andExpect(jsonPath("$.keyType", is("EMAIL")));
    }

    @Test
    void lookupIsCaseInsensitiveAndTheNormalisedKeyIsEchoedBack() throws Exception {
        // A payer typing mixed case must still resolve; echoing the normalised value is what makes a casing
        // surprise visible in a log instead of mysterious.
        mvc.perform(get("/spi/dict/{key}", "Bob@OtherBank.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key", is("bob@otherbank.com")))
                .andExpect(jsonPath("$.ispb", is("99999999")));
    }

    @Test
    void keysOfEveryKindTheDirectoryHoldsResolve() throws Exception {
        // A phone key carries a leading '+' and a CPF is bare digits: both are legal path segments, and
        // Spring Boot 3 no longer truncates a trailing ".com"-looking suffix either.
        mvc.perform(get("/spi/dict/{key}", "+5511977776666"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keyType", is("PHONE")))
                .andExpect(jsonPath("$.ispb", is("88888888")));

        mvc.perform(get("/spi/dict/{key}", "98765432100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keyType", is("CPF")));
    }

    @Test
    void aKeyNoParticipantHoldsIs404DictKeyNotFound() throws Exception {
        // The ONE case in which a payer should be told the key does not exist. Every other failure of this
        // call means "we could not ask", which account-service refuses to translate into "does not exist".
        mvc.perform(get("/spi/dict/{key}", "nobody@nowhere.com"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code", is("DICT_KEY_NOT_FOUND")));
    }

    @Test
    void theDictIsNotSubjectToTheSettlementFailureInjection() throws Exception {
        // The knobs exist to break settlement, which nobody waits for. This lookup sits on the synchronous
        // send path (p99 < 2s), so arming a 10s latency and a certain failure must leave it untouched —
        // otherwise every drill would also break key resolution and prove nothing about settlement.
        mvc.perform(post("/admin/config").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"latencyMs\":10000,\"failureRate\":1.0,\"timeoutRate\":1.0}"))
                .andExpect(status().isOk());
        try {
            long startedAt = System.nanoTime();
            mvc.perform(get("/spi/dict/{key}", "bob@otherbank.com"))
                    .andExpect(status().isOk());
            org.assertj.core.api.Assertions.assertThat((System.nanoTime() - startedAt) / 1_000_000L)
                    .isLessThan(1_000L);
        } finally {
            mvc.perform(post("/admin/config").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"latencyMs\":0,\"failureRate\":0.0,\"timeoutRate\":0.0}"));
        }
    }
}
