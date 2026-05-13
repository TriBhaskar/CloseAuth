package com.anterka.closeauthbackend.common.config.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;

import java.io.IOException;
import java.util.Map;

/**
 * Custom success handler for the OAuth2 form-login flow.
 *
 * Instead of Spring's default behaviour (302 redirect to "/"),
 * this handler returns HTTP 200 with a minimal JSON body.
 *
 * The BFF extracts the new JSESSIONID from the Set-Cookie header
 * and uses the 200 status to confirm authentication succeeded,
 * then resumes the OAuth2 authorization code flow on its own.
 *
 * This eliminates ambiguity: the BFF no longer needs to guess
 * whether a 302 redirect means "success" or "error".
 */
@Slf4j
public class OAuthLoginSuccessHandler implements AuthenticationSuccessHandler {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication) throws IOException {

        log.info("OAuth form login successful for user: {}", authentication.getName());

        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        Map<String, Object> body = Map.of(
                "authenticated", true,
                "username", authentication.getName()
        );

        objectMapper.writeValue(response.getWriter(), body);
    }
}

