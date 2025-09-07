package com.anterka.closeauthbackend.core.controller;

import com.anterka.closeauthbackend.core.ClientService;
import com.anterka.closeauthbackend.dto.CreateClientDto;
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

    @PostMapping("/create")
    public ResponseEntity<String> createClient(@RequestBody CreateClientDto dto) {
        log.info("Creating new client with ID: {}", dto.getClientId());
        return ResponseEntity.status(HttpStatus.CREATED).body(clientService.create(dto));
    }
}
