package com.anterka.closeauthbackend.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.HashMap;
import java.util.Map;

/**
 * Password hashing configuration (Section 13.1).
 *
 * <p>Uses Spring Security's {@link DelegatingPasswordEncoder}: every hash is stored with an
 * algorithm prefix (e.g. {@code {bcrypt}$2a$12$...}), so multiple algorithms can coexist and
 * the system can migrate between them <em>without a schema change</em> — old hashes keep
 * verifying against whichever prefixed encoder produced them.
 *
 * <p><b>Default = bcrypt (cost 12).</b> bcrypt ships with Spring Security ({@code spring-security-crypto})
 * so no extra dependency is required for Stage 3b. Section 13.1 prefers Argon2id; enabling it is a
 * deliberate dependency decision (Bouncy Castle {@code bcprov-jdk18on}) deferred for sign-off — see
 * STAGE_3B_REPORT.md. When approved, switching is a two-line change here (register an
 * {@code Argon2PasswordEncoder} under {@code "argon2"} and set it as the default id); existing bcrypt
 * hashes remain valid because of the prefix.
 */
@Configuration
public class PasswordEncoderConfig {

    /** The encoder id used for NEW hashes. Also mirrored into {@code user_identities.password_algo}. */
    public static final String DEFAULT_ENCODER_ID = "bcrypt";

    private static final int BCRYPT_STRENGTH = 12;

    @Bean
    public PasswordEncoder passwordEncoder() {
        Map<String, PasswordEncoder> encoders = new HashMap<>();
        encoders.put("bcrypt", new BCryptPasswordEncoder(BCRYPT_STRENGTH));
        // To enable Argon2id later (after adding the Bouncy Castle dependency):
        //   encoders.put("argon2", new Argon2PasswordEncoder(16, 32, 1, 1 << 14, 2));
        // then change DEFAULT_ENCODER_ID / the id below to "argon2".
        return new DelegatingPasswordEncoder(DEFAULT_ENCODER_ID, encoders);
    }
}
