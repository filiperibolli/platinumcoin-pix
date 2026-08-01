package com.platinumcoin.pix.auth.api;

import com.platinumcoin.pix.auth.domain.usecase.LoginUseCase;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Inbound adapter for {@code POST /v1/auth/login}. Per ADR-0011 it does three things and no more:
 * bean-validate the body, call one use case, map the result to the wire DTO. No secret, no password
 * and no token content is ever logged here; the business-stage line belongs to {@link LoginUseCase},
 * and a rejected login is logged by {@link AuthExceptionHandler}.
 */
@RestController
@RequestMapping("/v1/auth")
public class AuthController {

    private final LoginUseCase login;

    public AuthController(LoginUseCase login) {
        this.login = login;
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        return LoginResponse.from(login.execute(request.username(), request.password()));
    }
}
