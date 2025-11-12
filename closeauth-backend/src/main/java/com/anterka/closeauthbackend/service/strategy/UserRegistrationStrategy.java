package com.anterka.closeauthbackend.service.strategy;

import com.anterka.closeauthbackend.dto.request.UserRegistrationDto;
import com.anterka.closeauthbackend.entities.Users;

public interface UserRegistrationStrategy {
    Users createUser(UserRegistrationDto userRegistrationDto);
    void performPostRegistrationSetup(Users user, UserRegistrationDto userRegistrationDto);
}
