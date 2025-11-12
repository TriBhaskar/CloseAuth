package com.anterka.closeauthbackend.dto;

import com.anterka.closeauthbackend.dto.request.UserRegistrationDto;
import com.anterka.closeauthbackend.enums.GlobalRoleEnum;

public record RegistrationData(
        UserRegistrationDto registrationDto,
        GlobalRoleEnum globalRoleEnum
) {
}
