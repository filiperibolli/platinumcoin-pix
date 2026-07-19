package com.platinumcoin.pix.common.error;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.platinumcoin.pix.common.web.CorrelationId;
import com.platinumcoin.pix.common.web.CorrelationIdFilter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

class GlobalExceptionHandlerTest {

    private static final String SECRET = "boom-secret-internal-detail";

    private MockMvc mockMvc;

    @BeforeEach
    void setup() {
        mockMvc = standaloneSetup(new TestController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilter(new CorrelationIdFilter())
                .build();
    }

    @Test
    void domainExceptionMapsToItsStatusAsProblemJsonWithCodeAndCorrelationId() throws Exception {
        mockMvc.perform(get("/boom-domain"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().exists(CorrelationId.HEADER))
                .andExpect(jsonPath("$.code").value("LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.correlationId").value(not(emptyOrNullString())));
    }

    @Test
    void unexpectedExceptionMapsTo500WithoutLeakingStackTrace() throws Exception {
        mockMvc.perform(get("/boom-generic"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.correlationId").value(not(emptyOrNullString())))
                // The internal cause and any stack frames must never reach the client.
                .andExpect(content().string(not(containsString(SECRET))))
                .andExpect(content().string(not(containsString("java.lang"))))
                .andExpect(content().string(not(containsString(".java:"))));
    }

    @Test
    void validationErrorMapsTo400ProblemJsonWithCode() throws Exception {
        mockMvc.perform(post("/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.correlationId").value(not(emptyOrNullString())));
    }

    @RestController
    static class TestController {

        @org.springframework.web.bind.annotation.GetMapping("/boom-domain")
        void boomDomain() {
            throw new DomainException("LIMIT_EXCEEDED", HttpStatus.UNPROCESSABLE_ENTITY, "daily limit exceeded");
        }

        @org.springframework.web.bind.annotation.GetMapping("/boom-generic")
        void boomGeneric() {
            throw new IllegalStateException(SECRET);
        }

        @PostMapping("/validate")
        void validate(@Valid @RequestBody Payload payload) {
            // reached only when valid
        }
    }

    record Payload(@NotBlank String name) {}
}
