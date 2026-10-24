package com.clouddrop.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Type-safe binding of the "clouddrop.*" section of application.yml.
 * @Validated makes Spring check the constraints at startup: a missing or too-short
 * JWT secret stops the application from starting (fail fast).
 */
@Validated
@ConfigurationProperties(prefix = "clouddrop")
public record AppProperties(@Valid @NotNull Jwt jwt, @Valid @NotNull Storage storage) {

    public record Jwt(
            @NotBlank @Size(min = 32, message = "JWT_SECRET must be at least 32 characters") String secret,
            @Min(1) long expirationHours) {
    }

    public record Storage(
            @NotBlank String uploadDir,
            @Min(1) long maxFileSizeBytes) {
    }
}
