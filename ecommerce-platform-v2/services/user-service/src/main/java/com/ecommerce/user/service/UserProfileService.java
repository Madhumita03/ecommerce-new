package com.ecommerce.user.service;

import com.ecommerce.user.domain.entity.User;
import com.ecommerce.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserProfileService {

    private final UserRepository userRepository;

    @Transactional
    public UserResponse getOrCreate(Jwt jwt) {
        return userRepository.findByKeycloakId(jwt.getSubject())
            .map(this::toResponse)
            .orElseGet(() -> toResponse(userRepository.save(User.builder()
                .keycloakId(jwt.getSubject())
                .email(requiredEmail(jwt))
                .firstName(jwt.getClaimAsString("given_name"))
                .lastName(jwt.getClaimAsString("family_name"))
                .roles(extractRoles(jwt))
                .build())));
    }

    @Transactional
    public UserResponse update(Jwt jwt, UpdateProfileRequest request) {
        User user = userRepository.findByKeycloakId(jwt.getSubject())
            .orElseGet(() -> User.builder()
                .keycloakId(jwt.getSubject())
                .email(requiredEmail(jwt))
                .roles(extractRoles(jwt))
                .build());
        user.setFirstName(request.firstName());
        user.setLastName(request.lastName());
        return toResponse(userRepository.save(user));
    }

    private String requiredEmail(Jwt jwt) {
        String email = jwt.getClaimAsString("email");
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("The access token does not contain an email claim");
        }
        return email;
    }

    @SuppressWarnings("unchecked")
    private Set<String> extractRoles(Jwt jwt) {
        Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
        if (realmAccess == null) {
            return Set.of();
        }
        return new HashSet<>((List<String>) realmAccess.getOrDefault("roles", List.of()));
    }

    private UserResponse toResponse(User user) {
        return new UserResponse(
            user.getId(),
            user.getEmail(),
            user.getFirstName(),
            user.getLastName(),
            user.getStatus(),
            Set.copyOf(user.getRoles()),
            user.getCreatedAt(),
            user.getUpdatedAt());
    }

    public record UpdateProfileRequest(String firstName, String lastName) {
    }

    public record UserResponse(
        UUID id,
        String email,
        String firstName,
        String lastName,
        User.UserStatus status,
        Set<String> roles,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
    ) {
    }
}
