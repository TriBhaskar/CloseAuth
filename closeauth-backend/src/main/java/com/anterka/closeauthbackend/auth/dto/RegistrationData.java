package com.anterka.closeauthbackend.auth.dto;

import com.anterka.closeauthbackend.auth.dto.request.UserRegistrationDto;
import com.anterka.closeauthbackend.user.enums.GlobalRoleEnum;

public record RegistrationData(
        UserRegistrationDto registrationDto,
        GlobalRoleEnum globalRoleEnum
) {
}
