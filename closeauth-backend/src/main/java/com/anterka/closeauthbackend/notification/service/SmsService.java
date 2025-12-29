package com.anterka.closeauthbackend.notification.service;

import java.util.concurrent.CompletableFuture;

/**
 * Service interface for sending SMS messages.
 * Implementations can use various providers like Twilio, AWS SNS, etc.
 */
public interface SmsService {

    /**
     * Send an OTP to the specified phone number.
     *
     * @param phoneNumber The recipient's phone number
     * @param otp The OTP to send
     * @return CompletableFuture with true if sent successfully, false otherwise
     */
    CompletableFuture<Boolean> sendOtp(String phoneNumber, String otp);

    /**
     * Send a custom message to the specified phone number.
     *
     * @param phoneNumber The recipient's phone number
     * @param message The message to send
     * @return CompletableFuture with true if sent successfully, false otherwise
     */
    CompletableFuture<Boolean> sendMessage(String phoneNumber, String message);
}

