package com.ecommerce.user.controller;

import com.ecommerce.user.service.UserProfileService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class UserController {

    private final UserProfileService userProfileService;

    @GetMapping("/me")
    public ResponseEntity<UserProfileService.UserResponse> getCurrentUser(
            JwtAuthenticationToken authentication) {
        return ResponseEntity.ok(userProfileService.getOrCreate(authentication.getToken()));
    }

    @PutMapping("/me")
    public ResponseEntity<UserProfileService.UserResponse> updateCurrentUser(
            JwtAuthenticationToken authentication,
            @RequestBody UserProfileService.UpdateProfileRequest request) {
        return ResponseEntity.ok(userProfileService.update(authentication.getToken(), request));
    }
}
