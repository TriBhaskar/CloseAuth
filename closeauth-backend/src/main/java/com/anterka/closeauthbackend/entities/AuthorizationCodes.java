package com.anterka.closeauthbackend.entities;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@Entity
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Table(name = "authorization_codes")
public class AuthorizationCodes implements Serializable {

    private static final long serialVersionUID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(nullable = false, unique = true)
    private String code;

    @ManyToOne
    @JoinColumn(name = "user_id", nullable = false)
    private Users user;

    @ManyToOne
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenants tenant;

    @Column(name = "client_id", nullable = false)
    private String clientId;

    @Column(name = "redirect_uri", nullable = false)
    private String redirectUri;

    private String scopes;

    @Column(name = "code_challenge")
    private String codeChallenge;

    @Column(name = "code_challenge_method", length = 10)
    private String codeChallengeMethod;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    private Boolean used = false;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
