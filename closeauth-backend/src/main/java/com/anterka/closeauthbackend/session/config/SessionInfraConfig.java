package com.anterka.closeauthbackend.session.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Stage 5 session infrastructure (§7.5).
 *
 * <p><b>Two distinct Redis-backed session concerns — read this to understand the split:</b>
 * <ol>
 *   <li><b>The CloseAuth SSO session</b> (the domain "Auth Server session"): tenant-scoped, {@code session_key}
 *       cookie, idle/absolute/remember-me timeouts, revoke cascade, durable ledger. This is the <em>mechanism</em>
 *       Stage 5 builds — see {@code AuthServerSessionService}. It is managed over a <em>dedicated custom Redis hot
 *       store</em> ({@code RedisSessionHotStore}, key {@code closeauth:authsession:{sessionKey}}), NOT Spring
 *       Session — because tenant-scoped validation, precise timeout semantics, a controlled/round-trip-safe
 *       serialization (the Stage 4b-ii D1 lesson), and a unit-testable port are all things an opaque Spring Session
 *       {@code HttpSession} does not give us.</li>
 *   <li><b>Spring Session Data Redis</b> (the servlet {@code HttpSession} that backs Spring Security's authenticated
 *       principal during the interactive OAuth dance): wired as <em>infrastructure</em> so it is Redis-backed rather
 *       than in-memory, and thus survives horizontal scaling. This is enabled by the {@code spring-session-data-redis}
 *       dependency (Stage 0) + Boot auto-configuration driven by {@code spring.session.*} / {@code spring.data.redis.*}
 *       (see {@code application.yml}). Stage 5 does NOT tie the SSO skip-login decision to it — that consultation is
 *       the {@code /authorize} flow, which is Stage 6.</li>
 * </ol>
 *
 * <p>This class contributes the {@link Clock} used by the session mechanism (injected so idle/absolute timeouts are
 * deterministically unit-testable). Spring Session itself needs no bean here — it is Boot-auto-configured from
 * properties — which is the "use the framework, don't reinvent it" discipline the prompt asks for.
 */
@Configuration
public class SessionInfraConfig {

    /** UTC clock for session timestamp/timeout arithmetic. Overridable (e.g. a fixed clock) in tests. */
    @Bean
    @ConditionalOnMissingBean
    public Clock closeAuthClock() {
        return Clock.systemUTC();
    }
}
