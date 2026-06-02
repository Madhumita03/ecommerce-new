package com.ecommerce.user.domain.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Application user profile – auth delegated to Keycloak via OAuth2/OIDC. */
@Entity @Table(name = "users")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class User {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(name = "keycloak_id", nullable = false, unique = true, length = 100) private String keycloakId;
    @Column(nullable = false, unique = true, length = 255) private String email;
    @Column(name = "first_name", length = 100) private String firstName;
    @Column(name = "last_name",  length = 100) private String lastName;
    @Enumerated(EnumType.STRING) @Builder.Default private UserStatus status = UserStatus.ACTIVE;
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_roles", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "role") @Builder.Default private Set<String> roles = new HashSet<>();
    @CreationTimestamp @Column(name = "created_at", updatable = false) private LocalDateTime createdAt;
    @UpdateTimestamp   @Column(name = "updated_at")                    private LocalDateTime updatedAt;
    @Version private Long version;
    public enum UserStatus { ACTIVE, SUSPENDED, DELETED }
}
