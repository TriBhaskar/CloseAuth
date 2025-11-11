package com.anterka.closeauthbackend.core.controller;


import com.anterka.closeauthbackend.dto.UserRegistrationDto;
import com.anterka.closeauthbackend.service.AppUserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
@Slf4j
public class AuthController {

    private final AppUserService appUserService;

    // Protected endpoint for creating users (requires OAuth2 access token with 'client.create' scope)
    @PostMapping("/create")
    @PreAuthorize("hasAuthority('SCOPE_client.create')")
    public ResponseEntity<String> createUser(@RequestBody UserRegistrationDto userRegistrationDto) {
        log.info("Received authenticated user creation request for username: {}", userRegistrationDto.username());
        return ResponseEntity.status(HttpStatus.CREATED).body(appUserService.createUser(userRegistrationDto));
    }
}
