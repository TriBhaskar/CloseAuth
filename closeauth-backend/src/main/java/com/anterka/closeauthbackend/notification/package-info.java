/**
 * Outbound notification delivery — email, SMS (Phase 1 stub), webhooks (Phase 3).
 * Consumed by the auth, tenant, agent, and audit modules.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Email delivery (auth OTPs, password-reset links, and — later — tenant
 *       admin invitations, welcome emails, agent consent-grant notices).</li>
 *   <li>SMS delivery via a stub in Phase 1; a real provider is integrated later.</li>
 *   <li>Webhook dispatch for tenant/audit events (Phase 3 firehose, §7.11).</li>
 * </ul>
 *
 * <p>This is a cross-cutting delivery module: it is depended upon by auth, tenant,
 * agent, and audit, and must not depend on them (dependencies point inward, toward
 * notification).
 *
 * <p>References: Sections 10.3 (module layout), 8.1/8.5 (email), and 7.11 (audit
 * webhooks) of CLOSEAUTH_PRODUCT_VISION_V2.md.
 */
package com.anterka.closeauthbackend.notification;
