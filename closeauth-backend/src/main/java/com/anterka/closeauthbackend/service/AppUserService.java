package com.anterka.closeauthbackend.service;

import com.anterka.closeauthbackend.dto.UserRegistrationDto;
import com.anterka.closeauthbackend.entities.Users;
import com.anterka.closeauthbackend.enums.GlobalRoleEnum;
import com.anterka.closeauthbackend.repository.GlobalRolesRepository;
import com.anterka.closeauthbackend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class AppUserService {
    private final UserRepository userRepository;
    private final GlobalRolesRepository globalRolesRepository;
    private final PasswordEncoder passwordEncoder;

    public String createUser(UserRegistrationDto userRegistrationDto){
        Users user = Users.builder()
                .username(userRegistrationDto.username())
                .email(userRegistrationDto.email())
                .passwordHash(passwordEncoder.encode(userRegistrationDto.password()))
                .algo("bcrypt")
                .firstName(userRegistrationDto.firstName())
                .lastName(userRegistrationDto.lastName())
                .phone(userRegistrationDto.phone())
                .globalRoles(globalRolesRepository.findByRole(GlobalRoleEnum.END_USER).orElseThrow(()-> new RuntimeException("Default role not found")))
                .build();
        userRepository.save(user);
        log.info("User created: {}", user.getUsername());
        return "User created successfully";
    }
}
