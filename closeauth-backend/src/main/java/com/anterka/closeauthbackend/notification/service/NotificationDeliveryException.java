package com.anterka.closeauthbackend.notification.service;

/**
 * Raised when an out-of-band notification (verification code, magic-link, reset link, invite) fails to be delivered by
 * the transport. Carries only non-sensitive context (event type + recipient) — <b>never the secret code/link</b> — so
 * it is safe to log and to let propagate. The cause is the underlying transport failure (e.g. a Spring
 * {@code MailException}).
 */
public class NotificationDeliveryException extends RuntimeException {

    public NotificationDeliveryException(String eventType, String recipient, Throwable cause) {
        super("Failed to deliver " + eventType + " notification to " + recipient, cause);
    }
}
