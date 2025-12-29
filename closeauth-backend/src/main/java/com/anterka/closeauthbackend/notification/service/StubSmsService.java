package com.anterka.closeauthbackend.notification.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

/**
 * Stub implementation of SmsService for development/testing.
 * Logs OTP to console instead of sending actual SMS.
 * Replace with real implementation (Twilio, AWS SNS, etc.) in production.
 */
@Service
@Slf4j
public class StubSmsService implements SmsService {

    @Override
    @Async("virtualThreadExecutor")
    public CompletableFuture<Boolean> sendOtp(String phoneNumber, String otp) {
        log.info("=================================================");
        log.info("STUB SMS SERVICE - OTP Delivery");
        log.info("Phone Number: {}", phoneNumber);
        log.info("OTP: {}", otp);
        log.info("Message: Your verification code is: {}", otp);
        log.info("=================================================");

        // Simulate sending delay
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        return CompletableFuture.completedFuture(true);
    }

    @Override
    @Async("virtualThreadExecutor")
    public CompletableFuture<Boolean> sendMessage(String phoneNumber, String message) {
        log.info("=================================================");
        log.info("STUB SMS SERVICE - Message Delivery");
        log.info("Phone Number: {}", phoneNumber);
        log.info("Message: {}", message);
        log.info("=================================================");

        // Simulate sending delay
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        return CompletableFuture.completedFuture(true);
    }
}

