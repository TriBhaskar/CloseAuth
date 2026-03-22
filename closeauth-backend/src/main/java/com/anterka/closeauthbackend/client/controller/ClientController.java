package com.anterka.closeauthbackend.client.controller;

import com.anterka.closeauthbackend.client.service.ClientService;
import com.anterka.closeauthbackend.client.dto.CreateClientDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/clients")
@RequiredArgsConstructor
@Slf4j
public class ClientController {

    private final ClientService clientService;

    @PostMapping("/test-create")
    public ResponseEntity<String> testCreateClient(@RequestBody CreateClientDto dto) {
        log.info("Test creating new client with ID: {}", dto.getClientId());
        return ResponseEntity.status(HttpStatus.CREATED).body("Test client created: " + dto.getClientId());
    }
}
