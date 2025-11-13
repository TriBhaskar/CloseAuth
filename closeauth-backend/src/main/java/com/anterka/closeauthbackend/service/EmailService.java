package com.anterka.closeauthbackend.service;

import com.anterka.closeauthbackend.exception.EmailSendingException;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

@Service
@RequiredArgsConstructor
public class EmailService {

    private final JavaMailSender mailSender;
    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    @Async("virtualThreadExecutor")
    public CompletableFuture<Boolean> sendOTPMail(String to, String otp) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper messageHelper = new MimeMessageHelper(message, true);

            messageHelper.setTo(to);
            messageHelper.setSubject("OTP for Email Verification");
            messageHelper.setText("Your OTP for email verification is: " + otp);

            mailSender.send(message);
            log.info("OTP email sent successfully to: {}", to);
            return CompletableFuture.completedFuture(true);

        } catch (MessagingException | MailException e) {
            log.error("Failed to send OTP email to {}: {}", to, e.getMessage(), e);
            return CompletableFuture.completedFuture(false);
        }
    }

    public void sendForgotPasswordLinkMail(String to, String link, long expiresIn) throws MessagingException {
        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper messageHelper = new MimeMessageHelper(message, true);

        messageHelper.setTo(to);
        messageHelper.setSubject("Close Auth Forgot Password Link");
        messageHelper.setText("To reset your password, click the link below:\n\n" + link +
                "\n\nThis link will expire in " + expiresIn + " minutes.");

        try {
            mailSender.send(message);
            log.info("Forgot password email sent successfully");
        } catch (MailException e) {
            log.error("Failed to send the forgot password email: {}", e.getMessage());
            throw new EmailSendingException("Failed to process email verification request");
        }

    }
}
