package com.anterka.closeauthbackend.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import redis.clients.jedis.JedisPooled;

import java.security.SecureRandom;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class OtpService {
    private final JedisPooled client;

    private static final SecureRandom random = new SecureRandom();
    private static final int OTP_LENGTH = 6;

    private static final String OTP_PREFIX = "otp_";
    protected static final long OTP_VALIDITY_SECONDS = TimeUnit.SECONDS.toSeconds(600); // 10 minutes

    public long saveOtp(String email, String otp) {
        String key = OTP_PREFIX + email;
        client.setex(key, OTP_VALIDITY_SECONDS, otp);
        return OTP_VALIDITY_SECONDS;
    }

    public String getOtp(String email) {
        String key = OTP_PREFIX + email;
        return client.get(key);
    }

    public void deleteOtp(String email) {
        String key = OTP_PREFIX + email;
        client.del(key);
    }
    public String generateOtp() {
        StringBuilder otp = new StringBuilder(OTP_LENGTH);
        for (int i = 0; i < OTP_LENGTH; i++) {
            otp.append(random.nextInt(10));
        }
        return otp.toString();
    }
}
