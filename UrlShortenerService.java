package com.example.agentic_url_shortener.Service;

import com.example.agentic_url_shortener.Entity.*;
import com.example.agentic_url_shortener.Repository.ShortUrlRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service // Registers business logic class as a Spring bean
public class UrlShortenerService { // Core domain service for creating, resolving, and tracking URLs

    private static final String BASE62 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"; // 62 URL-safe characters
    private static final int CODE_LENGTH = 7; // 62^7 ≈ 3.5 trillion possible codes
    private static final SecureRandom RANDOM = new SecureRandom(); // Non-predictable random generator

    private final ShortUrlRepository repository; // Database persistence dependency
    private final Map<String, ShortUrl> cache = new ConcurrentHashMap<>(); // Fast read-through cache (can swap with Redis in prod)

    UrlShortenerService(ShortUrlRepository repository) { // Constructor injection makes unit testing easy
        this.repository = repository; // Store injected repository
    }

    @Transactional // Wraps creation in a DB transaction
    public ShortUrlResponse createShortUrl(String targetUrl, String baseUrl) { // Creates a new shortened URL
        String code = generateUniqueCodeWithFallback(targetUrl); // Generate collision-free code with fallback
        ShortUrl saved = repository.save(new ShortUrl(code, targetUrl, Instant.now())); // Persist entity to database
        cache.put(code, saved); // Populate cache so immediate redirects are fast
        return toResponse(saved, baseUrl); // Convert entity to API response DTO
    }

    @Transactional // Ensures redirect lookup and click counter update stay consistent
    public Optional<String> resolveAndTrackClick(String code) { // Resolves code to target URL and increments clicks
        ShortUrl entry = cache.computeIfAbsent(code, k -> repository.findByCode(k).orElse(null)); // Cache lookup, fallback to DB
        if (entry == null) { // Short code does not exist
            return Optional.empty(); // Return empty so controller can return 404
        }
        ShortUrl managed = repository.findByCode(code).orElse(entry); // Load managed entity in current transaction
        managed.incrementClicks(); // Increment click counter
        ShortUrl updated = repository.save(managed); // Save updated click count
        cache.put(code, updated); // Refresh cache entry with latest click count
        return Optional.of(updated.getTargetUrl()); // Return destination URL
    }

    public Optional<ShortUrlResponse> getStats(String code, String baseUrl) { // Fetches analytics without incrementing clicks
        return repository.findByCode(code).map(url -> toResponse(url, baseUrl)); // Map entity to DTO if present
    }

    private String generateUniqueCodeWithFallback(String targetUrl) { // Primary random generation + deterministic fallback
        for (int attempt = 0; attempt < 5; attempt++) { // Bounded retry loop (max 5 attempts)
            StringBuilder sb = new StringBuilder(CODE_LENGTH); // Builder for assembling short code
            for (int i = 0; i < CODE_LENGTH; i++) { // Pick CODE_LENGTH characters
                sb.append(BASE62.charAt(RANDOM.nextInt(BASE62.length()))); // Append random Base62 character
            }
            String candidate = sb.toString(); // Convert builder to String
            if (!repository.existsByCode(candidate)) { // Check uniqueness in DB
                return candidate; // Return unique code
            }
        }
        // Fallback if 5 random attempts collided: deterministic slice of targetUrl + timestamp hash
        String fallback = Integer.toUnsignedString((targetUrl + Instant.now()).hashCode(), 36); // Base36 unsigned hash
        return fallback.length() > CODE_LENGTH ? fallback.substring(0, CODE_LENGTH) : fallback; // Trim to max length
    }

    private ShortUrlResponse toResponse(ShortUrl url, String baseUrl) { // Maps entity to response DTO
        String normalizedBase = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl; // Trim trailing slash
        return new ShortUrlResponse(url.getCode(), normalizedBase + "/" + url.getCode(), url.getTargetUrl(), url.getClickCount()); // Build response
    }
}
