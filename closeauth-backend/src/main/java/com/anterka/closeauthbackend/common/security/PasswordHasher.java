package com.anterka.closeauthbackend.common.security;

import com.anterka.closeauthbackend.common.exception.CloseAuthDomainException;
import com.anterka.closeauthbackend.common.exception.ErrorCategory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Password hashing primitive over the configured {@link PasswordEncoder}
 * ({@link com.anterka.closeauthbackend.common.config.PasswordEncoderConfig}).
 *
 * <p>Encapsulates two things services need: (1) hashing a raw password into the stored value
 * <em>plus</em> the algorithm id to persist in {@code user_identities.password_algo}, and
 * (2) constant-interface verification. The algorithm id is derived from the hash's own
 * {@code {id}} prefix, so {@code password_algo} always reflects the real algorithm even if the
 * default encoder changes later.
 *
 * <p>Enforces the password length policy centrally so every credential path (create, add, change,
 * reset) is covered, not just HTTP-validated commands.
 */
@Component
public class PasswordHasher {

    /** Minimum password length (policy). No composition rules, aligned with NIST 800-63B. */
    public static final int MIN_LENGTH = 8;
    /**
     * Maximum password length in bytes.
     *
     * <p><b>This 72-byte cap is a bcrypt coupling, not a real security limit</b> — bcrypt silently
     * truncates its input to the first 72 bytes, so we reject longer inputs to avoid confusingly
     * ignoring trailing characters. If/when Argon2id becomes the default encoder (Section 13.1,
     * deferred to a Phase 3 hardening pass), this cap should be lifted to 128+ — Argon2id has no
     * such truncation. Because hashes are algorithm-prefixed, raising the cap is a safe, standalone change.
     */
    public static final int MAX_LENGTH_BYTES = 72;

    private final PasswordEncoder encoder;

    public PasswordHasher(PasswordEncoder encoder) {
        this.encoder = encoder;
    }

    /**
     * Hashes {@code rawPassword} and reports the algorithm used.
     *
     * @throws CloseAuthDomainException category {@link ErrorCategory#VALIDATION} if the password
     *         violates the length policy
     */
    public HashedPassword hash(String rawPassword) {
        requireValidLength(rawPassword);
        String encoded = encoder.encode(rawPassword);
        return new HashedPassword(encoded, extractAlgorithmId(encoded));
    }

    /** True iff {@code rawPassword} matches the stored {@code encodedPassword}. Never throws on mismatch. */
    public boolean matches(String rawPassword, String encodedPassword) {
        if (rawPassword == null || encodedPassword == null) {
            return false;
        }
        return encoder.matches(rawPassword, encodedPassword);
    }

    private void requireValidLength(String raw) {
        int byteLen = raw == null ? 0 : raw.getBytes(StandardCharsets.UTF_8).length;
        if (raw == null || raw.length() < MIN_LENGTH || byteLen > MAX_LENGTH_BYTES) {
            throw new CloseAuthDomainException(
                    ErrorCategory.VALIDATION,
                    "password.policy_violation",
                    "Password must be at least " + MIN_LENGTH + " characters and at most "
                            + MAX_LENGTH_BYTES + " bytes",
                    Map.of("minLength", MIN_LENGTH, "maxLengthBytes", MAX_LENGTH_BYTES));
        }
    }

    private static String extractAlgorithmId(String encoded) {
        if (encoded != null && encoded.startsWith("{")) {
            int close = encoded.indexOf('}');
            if (close > 1) {
                return encoded.substring(1, close);
            }
        }
        return "unknown";
    }

    /** A hashed password plus the algorithm id to store alongside it. */
    public record HashedPassword(String hash, String algorithm) {}
}
