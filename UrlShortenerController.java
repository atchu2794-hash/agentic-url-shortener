package com.example.agentic_url_shortener.Controller;

import com.example.agentic_url_shortener.Entity.*;
import com.example.agentic_url_shortener.Service.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.net.URI;

@RestController // Exposes HTTP endpoints returning JSON or redirects
class UrlShortenerController { // HTTP adapter layer for the URL shortener

    private final UrlShortenerService service; // Delegates business logic to service layer

    UrlShortenerController(UrlShortenerService service) { // Constructor injection
        this.service = service; // Store service reference
    }

    @PostMapping("/api/v1/urls") // Handles HTTP POST to shorten a URL
    public ResponseEntity<ShortUrlResponse> shorten(@RequestBody CreateUrlRequest request, // Validates JSON body
                                                    @RequestHeader(value = "Host", defaultValue = "localhost:8080") String host) { // Reads Host header
        ShortUrlResponse created = service.createShortUrl(request.targetUrl(), "http://" + host); // Create short URL
        return ResponseEntity.status(HttpStatus.CREATED).body(created); // Return HTTP 201 Created with JSON payload
    }

    @GetMapping("/{code:[a-zA-Z0-9]{1,16}}") // Handles HTTP GET redirect for short codes
    public ResponseEntity<Void> redirect(@PathVariable String code) { // Extracts {code} from URL path
        return service.resolveAndTrackClick(code) // Look up URL and record click
                .map(target -> ResponseEntity.status(HttpStatus.FOUND) // HTTP 302 Found for temporary redirect
                        .location(URI.create(target)) // Set Location header to original URL
                        .<Void>build()) // Build empty response body
                .orElseGet(() -> ResponseEntity.notFound().build()); // Return HTTP 404 if code is unknown
    }

    @GetMapping("/api/v1/urls/{code}/stats") // Handles HTTP GET for click analytics
    public ResponseEntity<ShortUrlResponse> stats(@PathVariable String code, // Extracts {code} path variable
                                                  @RequestHeader(value = "Host", defaultValue = "localhost:8080") String host) { // Reads Host header
        return service.getStats(code, "http://" + host) // Fetch stats from service
                .map(ResponseEntity::ok) // Return HTTP 200 OK if found
                .orElseGet(() -> ResponseEntity.notFound().build()); // Return HTTP 404 if not found
    }
}
