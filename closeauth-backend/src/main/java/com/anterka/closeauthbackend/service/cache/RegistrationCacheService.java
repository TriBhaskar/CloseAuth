package com.anterka.closeauthbackend.service.cache;

import com.anterka.closeauthbackend.dto.RegistrationData;
import com.google.gson.Gson;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import redis.clients.jedis.JedisPooled;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Service
@AllArgsConstructor
public class RegistrationCacheService {

    private final JedisPooled client;
    private final Gson gson;

    private static final String REGISTRATION_PREFIX = "registration_";
    private static final long REGISTRATION_VALIDITY_SECONDS = TimeUnit.HOURS.toSeconds(2);

    public void saveRegistration(String email, RegistrationData registrationRequest) {
        String key = REGISTRATION_PREFIX + email;
        client.jsonSet(key, gson.toJson(registrationRequest));
        client.expire(key, REGISTRATION_VALIDITY_SECONDS);
    }

    public Optional<RegistrationData> getRegistration(String email) {
        String key = REGISTRATION_PREFIX + email;
        Object jsonResult = client.jsonGet(key);
        if (jsonResult == null) {
            return Optional.empty();
        }
        // Convert the JSON object to string first
        String jsonStr = gson.toJson(jsonResult);
        RegistrationData request = gson.fromJson(jsonStr, RegistrationData.class);
        return Optional.ofNullable(request);
    }

    public boolean registrationExists(String email) {
        String key = REGISTRATION_PREFIX + email;
        return client.exists(key);
    }

    public void deleteRegistration(String email) {
        String key = REGISTRATION_PREFIX + email;
        client.del(key);
    }
}
