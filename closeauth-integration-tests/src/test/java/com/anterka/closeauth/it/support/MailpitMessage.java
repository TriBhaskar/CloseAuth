package com.anterka.closeauth.it.support;

import java.util.List;

/**
 * A captured email as read from Mailpit's REST API — just the fields the journeys need: the recipients, the subject,
 * and the plain-text body (from which one-time codes / links are extracted). Deep email-template assertion is not a
 * goal.
 */
public record MailpitMessage(String id, List<String> recipients, String subject, String text) {
}
