package com.anterka.closeauthbackend.identity.dto;

import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the contract that {@link UserView} never carries credential material. If someone later adds a
 * password/hash/secret field to the view, this fails loudly.
 */
class UserViewTest {

    @Test
    void userViewExposesNoCredentialFields() {
        String[] forbidden = {"password", "hash", "algo", "secret", "credential"};
        for (RecordComponent component : UserView.class.getRecordComponents()) {
            String name = component.getName().toLowerCase();
            assertThat(Arrays.stream(forbidden).anyMatch(name::contains))
                    .as("UserView component '%s' must not expose credential material", component.getName())
                    .isFalse();
        }
    }
}
