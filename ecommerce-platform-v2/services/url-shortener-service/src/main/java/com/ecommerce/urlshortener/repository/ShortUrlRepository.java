package com.ecommerce.urlshortener.repository;
import com.ecommerce.urlshortener.domain.ShortUrl;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;
public interface ShortUrlRepository extends JpaRepository<ShortUrl, Long> {
    Optional<ShortUrl> findByCode(String code);
    @Modifying @Query("UPDATE ShortUrl s SET s.clickCount = s.clickCount + 1 WHERE s.code = :code")
    void incrementClickCount(@Param("code") String code);
}
