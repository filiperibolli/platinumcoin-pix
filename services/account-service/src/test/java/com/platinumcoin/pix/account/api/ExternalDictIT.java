package com.platinumcoin.pix.account.api;

import com.platinumcoin.pix.common.testsupport.LocalStackTestBase;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * External key resolution end to end over HTTP — the step-11 seam, closed (step 30).
 *
 * <p><b>Why a real HTTP stub and not a fake bean.</b> The new code in this step is an <i>adapter</i>: it
 * builds a URI containing an {@code @}, parses a foreign JSON body, distinguishes a {@code 404} from every
 * other failure and enforces a timeout. Substituting the {@link com.platinumcoin.pix.account.domain.port.ExternalDirectory}
 * bean with a fake would test the use case (already covered by {@code ResolvePixKeyUseCaseTest}) and leave
 * exactly that adapter unexercised. So a ~40-line JDK {@link HttpServer} impersonates mock-bacen's
 * {@code GET /spi/dict/{key}} — no new dependency, real sockets, real status codes.
 *
 * <p>The stub records the paths it was asked for, which is how "the local table is consulted first" is
 * proven rather than assumed: an internal key must resolve with the stub never touched.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ExternalDictIT extends LocalStackTestBase {

    /** Keys this fake DICT answers for, mirroring mock-bacen's seeded {@code bacen.dict}. */
    private static final Map<String, String> DICT = Map.of(
            "bob@otherbank.com",
            "{\"key\":\"bob@otherbank.com\",\"keyType\":\"EMAIL\",\"ispb\":\"99999999\","
                    + "\"participant\":\"Banco OtherBank S.A.\"}",
            "dana@otherbank.com",
            // A key kind PlatinumCoin has no constant for: the resolution must still succeed on the ISPB.
            "{\"key\":\"dana@otherbank.com\",\"keyType\":\"SOMETHING_NEW\",\"ispb\":\"77777777\","
                    + "\"participant\":\"Banco Terceiro S.A.\"}");

    private static final List<String> REQUESTED_PATHS = new CopyOnWriteArrayList<>();

    private static HttpServer dictStub;

    @Autowired
    MockMvc mvc;

    @BeforeAll
    static void startTheFakeDict() throws IOException {
        dictStub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        dictStub.createContext("/spi/dict/", ExternalDictIT::answerDictLookup);
        dictStub.start();
    }

    @AfterAll
    static void stopTheFakeDict() {
        dictStub.stop(0);
    }

    @DynamicPropertySource
    static void pointAccountServiceAtTheFakeDict(DynamicPropertyRegistry registry) {
        registry.add("services.bacen.base-url",
                () -> "http://127.0.0.1:" + dictStub.getAddress().getPort());
    }

    @BeforeEach
    void forgetPreviousRequests() {
        REQUESTED_PATHS.clear();
    }

    private static void answerDictLookup(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        REQUESTED_PATHS.add(path);
        String key = URLDecoder.decode(path.substring("/spi/dict/".length()), StandardCharsets.UTF_8);
        String entry = DICT.get(key);

        byte[] body = entry != null ? entry.getBytes(StandardCharsets.UTF_8)
                : ("{\"type\":\"about:blank\",\"status\":404,\"code\":\"DICT_KEY_NOT_FOUND\","
                        + "\"detail\":\"No participant holds the Pix key " + key + "\"}")
                        .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type",
                entry != null ? "application/json" : "application/problem+json");
        exchange.sendResponseHeaders(entry != null ? 200 : 404, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    @Test
    void aKeyHeldAtAnotherParticipantNowResolvesExternally() throws Exception {
        // The assertion step 11 left red: bob@otherbank.com is registered nowhere in PlatinumCoin, and until
        // BACEN's DICT existed that meant 404. Now it means "another PSP holds it" — which is what makes the
        // external send branch (debit to clearing, settle asynchronously) reachable over HTTP at last.
        mvc.perform(get("/internal/pix-keys/resolve").param("key", "bob@otherbank.com")
                        .header("Authorization", "Bearer " + TestTokens.forUser("u-alice", "acc-001")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.internal", is(false)))
                .andExpect(jsonPath("$.externalBank", is("99999999")))
                .andExpect(jsonPath("$.keyType", is("EMAIL")))
                // No local account is named for a key held elsewhere — that is the point of the two branches.
                .andExpect(jsonPath("$.accountId", nullValue()));

        assertThat(REQUESTED_PATHS).containsExactly("/spi/dict/bob@otherbank.com");
    }

    @Test
    void theKeyIsDelegatedInItsNormalisedForm() throws Exception {
        // A payer typing mixed case must reach the DICT with the same value the local table was searched
        // for; otherwise the two directories would disagree about what "the same key" means.
        mvc.perform(get("/internal/pix-keys/resolve").param("key", "  Bob@OtherBank.com  ")
                        .header("Authorization", "Bearer " + TestTokens.forUser("u-alice", "acc-001")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.externalBank", is("99999999")));

        assertThat(REQUESTED_PATHS).containsExactly("/spi/dict/bob@otherbank.com");
    }

    @Test
    void aForeignKeyKindDoesNotSinkAnOtherwisePayableResolution() throws Exception {
        mvc.perform(get("/internal/pix-keys/resolve").param("key", "dana@otherbank.com")
                        .header("Authorization", "Bearer " + TestTokens.forUser("u-alice", "acc-001")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.internal", is(false)))
                .andExpect(jsonPath("$.externalBank", is("77777777")))
                .andExpect(jsonPath("$.keyType", nullValue()));
    }

    @Test
    void unknownInBothDirectoriesIsStill404KeyNotFound() throws Exception {
        // With the DICT up and answering 404, "not found" is finally the truth rather than a placeholder.
        mvc.perform(get("/internal/pix-keys/resolve").param("key", "nobody@nowhere.com")
                        .header("Authorization", "Bearer " + TestTokens.forUser("u-alice", "acc-001")))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code", is("KEY_NOT_FOUND")));

        assertThat(REQUESTED_PATHS).containsExactly("/spi/dict/nobody@nowhere.com");
    }

    @Test
    void anInternalKeyStillWinsAndNeverReachesTheDict() throws Exception {
        String token = "Bearer " + TestTokens.forUser("u-heidi", "acc-heidi");
        mvc.perform(post("/v1/pix-keys").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keyType\":\"EMAIL\",\"keyValue\":\"heidi@platinum.com\"}"))
                .andExpect(status().isCreated());
        REQUESTED_PATHS.clear();

        mvc.perform(get("/internal/pix-keys/resolve").param("key", "heidi@platinum.com")
                        .header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.internal", is(true)))
                .andExpect(jsonPath("$.accountId", is("acc-heidi")));

        // The hottest read in the platform pays no network hop for a key we already hold.
        assertThat(REQUESTED_PATHS).isEmpty();
    }
}
