package com.platinumcoin.pix.auth.api;

import com.platinumcoin.pix.auth.domain.AuthenticationService;
import com.platinumcoin.pix.auth.domain.IssuedToken;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Inbound adapter for {@code POST /v1/auth/login}. Validates the body, delegates the actual
 * authentication to the domain service, and maps the result to the wire DTO. No secret, no
 * password, and no token content is ever logged.
 */
@RestController
@RequestMapping("/v1/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final AuthenticationService authenticationService;

    public AuthController(AuthenticationService authenticationService) {
        this.authenticationService = authenticationService;
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        IssuedToken token = authenticationService.login(request.username(), request.password());
        log.info("auth.login.success username={}", request.username());
        return LoginResponse.from(token);
    }
}
