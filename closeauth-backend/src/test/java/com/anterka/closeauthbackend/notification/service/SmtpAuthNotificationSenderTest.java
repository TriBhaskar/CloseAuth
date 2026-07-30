package com.anterka.closeauthbackend.notification.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Unit test for the real SMTP sender against a mocked {@link JavaMailSender}: it is invoked with the right
 * recipient/subject/body per notification type, the secret code/link NEVER appears in logs (the discipline the logging
 * placeholder already had), and a delivery failure is surfaced as a {@link NotificationDeliveryException} without
 * leaking the secret. A live-SMTP test belongs to the separate integration-test module, not here.
 */
class SmtpAuthNotificationSenderTest {

    private static final String FROM = "no-reply@closeauth.test";
    private static final String TO = "alice@example.com";

    private JavaMailSender mailSender;
    private SmtpAuthNotificationSender sender;
    private ListAppender<ILoggingEvent> logAppender;
    private Logger senderLogger;

    @BeforeEach
    void setUp() {
        mailSender = mock(JavaMailSender.class);
        sender = new SmtpAuthNotificationSender(mailSender, FROM);

        senderLogger = (Logger) LoggerFactory.getLogger(SmtpAuthNotificationSender.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        senderLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        senderLogger.detachAppender(logAppender);
    }

    // ---- message shape per type -------------------------------------------

    @Test
    void verificationCodeEmailHasCorrectShape() {
        sender.sendEmailVerificationCode(TO, "123456");

        SimpleMailMessage sent = capture();
        assertThat(sent.getFrom()).isEqualTo(FROM);
        assertThat(sent.getTo()).containsExactly(TO);
        assertThat(sent.getSubject()).isNotBlank();
        assertThat(sent.getText()).contains("123456"); // the code is in the body — that is the point
    }

    @Test
    void magicLinkEmailCarriesTheUrl() {
        String url = "https://auth.example/magic-link/consume?token=opaque-secret-abc";
        sender.sendMagicLink(TO, url);
        assertThat(capture().getText()).contains(url);
    }

    @Test
    void passwordResetEmailCarriesTheUrl() {
        String url = "https://auth.example/reset-password?token=opaque-secret-def";
        sender.sendPasswordResetLink(TO, url);
        assertThat(capture().getText()).contains(url);
    }

    @Test
    void inviteEmailCarriesTheUrl() {
        String url = "https://auth.example/register?invite=opaque-secret-ghi";
        sender.sendInviteLink(TO, url);
        assertThat(capture().getText()).contains(url);
    }

    // ---- secret-logging discipline ----------------------------------------

    @Test
    void neverLogsTheSecretOnSuccess() {
        String code = "987654";
        String magic = "https://auth.example/magic-link/consume?token=super-secret-xyz";
        sender.sendEmailVerificationCode(TO, code);
        sender.sendMagicLink(TO, magic);

        assertThat(logMessages())
                .isNotEmpty() // it DOES log that a send happened (recipient + event), just not the secret
                .noneMatch(line -> line.contains(code))
                .noneMatch(line -> line.contains(magic));
    }

    // ---- failure surfacing (without leaking the secret) -------------------

    @Test
    void deliveryFailureThrowsAndDoesNotLeakTheSecret() {
        String code = "555000";
        doThrow(new MailSendException("SMTP connection refused")).when(mailSender).send(any(SimpleMailMessage.class));

        assertThatThrownBy(() -> sender.sendEmailVerificationCode(TO, code))
                .isInstanceOf(NotificationDeliveryException.class);

        assertThat(logMessages())
                .anyMatch(line -> line.contains("FAILED") && line.contains(TO)) // logged with context
                .noneMatch(line -> line.contains(code));                         // but never the secret
    }

    // ---- helpers ----------------------------------------------------------

    private SimpleMailMessage capture() {
        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        return captor.getValue();
    }

    private java.util.List<String> logMessages() {
        return logAppender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }
}
