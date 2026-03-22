package com.anterka.closeauthbackend.auth.strategy;

import com.anterka.closeauthbackend.auth.dto.request.UserRegistrationDto;
import com.anterka.closeauthbackend.user.entity.Users;
import com.anterka.closeauthbackend.user.enums.GlobalRoleEnum;
import com.anterka.closeauthbackend.common.exception.UserRegistrationException;
import com.anterka.closeauthbackend.user.repository.GlobalRolesRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ClientUserRegistrationStrategy implements UserRegistrationStrategy{

    private final GlobalRolesRepository globalRolesRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public Users createUser(UserRegistrationDto userRegistrationDto) {
             return Users.builder()
                .username(userRegistrationDto.username())
                .email(userRegistrationDto.email())
                .passwordHash(passwordEncoder.encode(userRegistrationDto.password()))
                .algo("bcrypt")
                .firstName(userRegistrationDto.firstName())
                .lastName(userRegistrationDto.lastName())
                .phone(userRegistrationDto.phone())
                .globalRoles(globalRolesRepository.findByRole(GlobalRoleEnum.END_USER).orElseThrow(()-> new UserRegistrationException("Default role not found")))
                .build();
    }

    @Override
    public void performPostRegistrationSetup(Users user, UserRegistrationDto userRegistrationDto) {

    }
}
