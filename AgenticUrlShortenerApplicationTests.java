package com.example.agentic_url_shortener;

import com.example.agentic_url_shortener.Entity.ShortUrlResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.*;
import org.springframework.test.web.servlet.client.RestTestClient;

import java.util.Map;

@SpringBootTest
class AgenticUrlShortenerApplicationTests {
	@Autowired
	private RestTestClient restTemplate;

	@Test
	void shouldCreateAndResolveShortUrl() {

		restTemplate.post()
				.uri("/api/v1/urls")
				.contentType(MediaType.APPLICATION_JSON)
				.body(Map.of("targetUrl", "https://spring.io"))
				.exchange()
				.expectStatus().isCreated()
				.expectBody(ShortUrlResponse.class)
				.value(res -> {
                    assert res != null;
                    if (res.code() == null || res.code().isBlank()) {
						throw new AssertionError("Expected non-blank short code");
					}
				});

	}

	/*@Test
	void contextLoads() {
	}*/

}
