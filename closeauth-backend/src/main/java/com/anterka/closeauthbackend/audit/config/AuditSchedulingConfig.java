package com.anterka.closeauthbackend.audit.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables Spring's scheduler so the audit pipeline's background jobs run: the outbox drain worker
 * ({@code AuditOutboxDrainWorker}) and the retention cleanup ({@code AuditRetentionJob}). Scoped to a dedicated config
 * (rather than the main application class) so scheduling's presence is discoverable and tied to the audit stage that
 * introduced it. Stage 8 is the first stage to need {@code @Scheduled}.
 */
@Configuration
@EnableScheduling
public class AuditSchedulingConfig {
}
