package com.platinumcoin.pix.common.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Lets any controller inject the caller by declaring an {@link AuthenticatedUser} parameter —
 * the {@code @AuthenticationPrincipal}-style seam, without pulling in the whole Spring Security
 * filter chain (ADR-0007 keeps auth-service starter-security-free). The value is whatever
 * {@link JwtAuthFilter} stashed on the request; on an allow-listed route (no principal set) it
 * resolves to {@code null}.
 */
public class AuthenticatedUserArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return AuthenticatedUser.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        HttpServletRequest request = webRequest.getNativeRequest(HttpServletRequest.class);
        return request == null ? null : request.getAttribute(AuthenticatedUser.REQUEST_ATTRIBUTE);
    }
}
