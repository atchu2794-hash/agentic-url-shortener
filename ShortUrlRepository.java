package com.example.agentic_url_shortener.Repository;

import com.example.agentic_url_shortener.Entity.ShortUrl;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ShortUrlRepository extends JpaRepository<ShortUrl, Long> { // Repository interface backed by Spring Data JPA
    Optional<ShortUrl> findByCode(String code); // Derived query: SELECT * FROM short_urls WHERE code = ?
    boolean existsByCode(String code); // Derived query to check if a code is already taken
}
