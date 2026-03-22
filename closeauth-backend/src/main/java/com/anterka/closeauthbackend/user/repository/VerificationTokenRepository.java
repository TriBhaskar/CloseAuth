package com.anterka.closeauthbackend.user.repository;

import com.anterka.closeauthbackend.user.entity.VerificationTokens;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface VerificationTokenRepository extends JpaRepository<VerificationTokens, Integer> {
    Optional<VerificationTokens> findByTokenHashAndUsedFalse(String tokenHash);
    void deleteByUserId(Integer userId);
}
