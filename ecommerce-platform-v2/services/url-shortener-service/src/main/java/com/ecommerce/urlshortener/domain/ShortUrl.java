package com.ecommerce.urlshortener.domain;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity @Table(name = "short_urls")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class ShortUrl {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, unique = true, length = 12)   private String code;
    @Column(name = "original_url", nullable = false, length = 2048) private String originalUrl;
    @Column(name = "click_count")  @Builder.Default private long clickCount = 0;
    @Column(name = "created_at")   @Builder.Default private LocalDateTime createdAt = LocalDateTime.now();
    @Column(name = "expires_at")   private LocalDateTime expiresAt;
    @Column(name = "created_by", length = 100) private String createdBy;
}
