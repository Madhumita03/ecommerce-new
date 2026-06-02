package com.ecommerce.user.service;

import com.ecommerce.user.security.UserSecurityConfig;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;

/**
 * Unit tests for Keycloak JWT roles extractor.
 * JUnit 5.12 + Mockito 5.17 | SLF4J implicit.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("KeycloakRolesConverter – unit tests")
class KeycloakRolesConverterTest {

    private final UserSecurityConfig.KeycloakRolesConverter converter =
        new UserSecurityConfig.KeycloakRolesConverter();

    @Mock Jwt jwt;

    @Test
    @DisplayName("maps realm_access.roles to ROLE_ prefixed authorities")
    void shouldMapRolesToGrantedAuthorities() {
        given(jwt.getClaimAsMap("realm_access"))
            .willReturn(Map.of("roles", List.of("admin", "user")));

        Collection<GrantedAuthority> authorities = converter.convert(jwt);

        assertThat(authorities).extracting(GrantedAuthority::getAuthority)
            .containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_USER");
    }

    @Test
    @DisplayName("returns empty collection when realm_access claim is absent")
    void shouldReturnEmpty_whenRealmAccessAbsent() {
        given(jwt.getClaimAsMap("realm_access")).willReturn(null);
        assertThat(converter.convert(jwt)).isEmpty();
    }

    @Test
    @DisplayName("returns empty collection when roles list is empty")
    void shouldReturnEmpty_whenRolesListEmpty() {
        given(jwt.getClaimAsMap("realm_access"))
            .willReturn(Map.of("roles", List.of()));
        assertThat(converter.convert(jwt)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"user", "admin", "inventory", "vendor"})
    @DisplayName("each role gets ROLE_ prefix and uppercased")
    void shouldUpperCaseAndPrefixRole(String role) {
        given(jwt.getClaimAsMap("realm_access"))
            .willReturn(Map.of("roles", List.of(role)));

        Collection<GrantedAuthority> authorities = converter.convert(jwt);
        assertThat(authorities).hasSize(1);
        assertThat(authorities.iterator().next().getAuthority())
            .isEqualTo("ROLE_" + role.toUpperCase());
    }
}
