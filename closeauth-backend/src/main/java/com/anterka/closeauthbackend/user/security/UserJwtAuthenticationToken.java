package com.anterka.closeauthbackend.user.security;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;

import java.util.Collection;

/**
 * Custom authentication token that holds user ID from JWT claims.
 * This allows stateless authentication with direct access to user ID
 * without requiring database lookups for ownership verification.
 */
public class UserJwtAuthenticationToken extends AbstractAuthenticationToken {

    private final String username;
    private final Integer userId;

    public UserJwtAuthenticationToken(
            String username,
            Integer userId,
            Collection<? extends GrantedAuthority> authorities) {
        super(authorities);
        this.username = username;
        this.userId = userId;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return null; // No credentials needed for JWT
    }

    @Override
    public Object getPrincipal() {
        return username;
    }

    public String getUsername() {
        return username;
    }

    public Integer getUserId() {
        return userId;
    }
}

