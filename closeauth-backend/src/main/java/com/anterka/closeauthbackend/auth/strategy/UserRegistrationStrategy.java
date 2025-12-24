package com.anterka.closeauthbackend.auth.strategy;

import com.anterka.closeauthbackend.auth.dto.request.UserRegistrationDto;
import com.anterka.closeauthbackend.user.entity.Users;

public interface UserRegistrationStrategy {
    Users createUser(UserRegistrationDto userRegistrationDto);
    void performPostRegistrationSetup(Users user, UserRegistrationDto userRegistrationDto);
}
