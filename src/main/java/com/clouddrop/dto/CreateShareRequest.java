package com.clouddrop.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

// expiresInMinutes is optional (default 24 hours). Min 1 minute, max 30 days.
public record CreateShareRequest(
        @Min(1) @Max(43200) Integer expiresInMinutes) {
}
