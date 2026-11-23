package com.example.agentic_url_shortener.Entity;

public record ShortUrlResponse( // Immutable DTO returned when creating or inspecting a short URL
                         String code, // Generated short code
                         String shortUrl, // Full shortened URL
                         String targetUrl, // Original destination URL
                         long clickCount // Total redirects recorded
) {}
