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
@Table(name = "credentials")
public class Credentials implements Serializable {

    private static final long serialVersionUID = 1L;

    @Id
    private Integer userId;

    @OneToOne
    @MapsId
    @JoinColumn(name = "user_id")
    private Users user;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    private String algo = "argon2id";

    @Column(name = "mfa_enabled")
    private Boolean mfaEnabled = false;

    @Column(name = "failed_attempts")
    private Integer failedAttempts = 0;

    @Column(name = "locked_until")
    private LocalDateTime lockedUntil;

    @Column(name = "password_changed_at")
    private LocalDateTime passwordChangedAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        passwordChangedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
