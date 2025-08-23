package com.anterka.closeauthbackend.core.entities;

import jakarta.persistence.*;
import lombok.*;

import java.io.Serializable;
import java.util.Objects;

@Data
@Entity
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Table(name = "oauth2_authorization_consent")
@IdClass(AuthorizationConsent.AuthorizationConsentId.class)
public class AuthorizationConsent implements Serializable {

    private static final long serialVersionUID = 1L;

    @Id
    @Column(name = "registered_client_id")
    private String registeredClientId;

    @Id
    @Column(name = "principal_name")
    private String principalName;

    @Column(name = "authorities", length = 1000, nullable = false)
    private String authorities;

    @Getter
    @Setter
    public static class AuthorizationConsentId implements Serializable {
        private String registeredClientId;
        private String principalName;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            AuthorizationConsentId that = (AuthorizationConsentId) o;
            return registeredClientId.equals(that.registeredClientId) && principalName.equals(that.principalName);
        }

        @Override
        public int hashCode() {
            return Objects.hash(registeredClientId, principalName);
        }
    }
}
