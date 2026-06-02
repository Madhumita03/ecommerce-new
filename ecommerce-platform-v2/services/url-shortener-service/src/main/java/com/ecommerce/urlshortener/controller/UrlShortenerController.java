package com.ecommerce.urlshortener.controller;

import com.ecommerce.urlshortener.service.UrlShortenerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.time.LocalDateTime;

@RestController @RequiredArgsConstructor
@Tag(name = "URL Shortener", description = "Shorten and resolve URLs")
public class UrlShortenerController {

    private final UrlShortenerService service;

    @PostMapping("/urls/shorten")
    @Operation(summary = "Shorten a URL")
    public ResponseEntity<ShortenResponse> shorten(@RequestBody ShortenRequest req) {
        String code = service.shorten(req.url(), req.expiresAt(), req.createdBy());
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(new ShortenResponse(code, "https://ec.app/s/" + code));
    }

    @GetMapping("/s/{code}")
    @Operation(summary = "Redirect to original URL (301)")
    public ResponseEntity<Void> redirect(@PathVariable String code) {
        return ResponseEntity.status(HttpStatus.MOVED_PERMANENTLY)
            .location(URI.create(service.resolve(code))).build();
    }

    record ShortenRequest(@NotBlank String url, LocalDateTime expiresAt, String createdBy) {}
    record ShortenResponse(String code, String shortUrl) {}
}
