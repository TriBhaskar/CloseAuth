package com.anterka.closeauthbackend.common.validation;

import com.anterka.closeauthbackend.common.exception.CloseAuthDomainException;
import com.anterka.closeauthbackend.common.exception.ErrorCategory;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Service-side half of Convention 5 (validation): programmatically runs the Jakarta Bean
 * Validation constraints declared on a command DTO and raises a
 * {@link CloseAuthDomainException} with category {@link ErrorCategory#VALIDATION} on failure.
 *
 * <p>This is <b>defense-in-depth, not a parallel validation system</b>: the annotations on the
 * command records remain the single source of truth. The HTTP edge (Stage 7, {@code @Valid})
 * and the service layer both invoke those same annotations. Services call this at the top of
 * mutating methods so that <em>non-HTTP</em> callers — signup orchestration, {@code SPI}
 * callbacks, future SDK flows, Token Exchange — get the same structural guarantees as HTTP
 * callers instead of failing later on a database constraint.
 */
@Component
public class CommandValidator {

    private final Validator validator;

    public CommandValidator(Validator validator) {
        this.validator = validator;
    }

    /**
     * Validates {@code command} against its Bean Validation annotations.
     *
     * @throws CloseAuthDomainException category {@link ErrorCategory#VALIDATION}, code
     *         {@code validation.failed}, with each violated property → message in the context map
     */
    public <T> void validate(T command) {
        Set<ConstraintViolation<T>> violations = validator.validate(command);
        if (violations.isEmpty()) {
            return;
        }

        Map<String, Object> context = new LinkedHashMap<>();
        for (ConstraintViolation<T> v : violations) {
            context.put(v.getPropertyPath().toString(), v.getMessage());
        }
        String detail = violations.stream()
                .map(v -> v.getPropertyPath() + " " + v.getMessage())
                .sorted()
                .collect(Collectors.joining("; "));

        throw new CloseAuthDomainException(
                ErrorCategory.VALIDATION,
                "validation.failed",
                "Command validation failed: " + detail,
                context);
    }
}
