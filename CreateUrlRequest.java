package com.example.agentic_url_shortener.Entity;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record CreateUrlRequest( // Immutable DTO for incoming POST requests
                         @NotBlank(message = "targetUrl must not be blank") // Rejects null, "", or whitespace-only URLs
                         @Pattern(regexp = "^https?://.+", message = "targetUrl must start with http:// or https://") // Policy guardrail: blocks javascript:/file:/data: schemes
                         String targetUrl // Original URL supplied by caller
) {}
