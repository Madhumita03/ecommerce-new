package com.ecommerce.order.controller;

import com.ecommerce.order.domain.dto.OrderDtos;
import com.ecommerce.order.service.OrderApplicationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
@Tag(name = "Orders", description = "Order checkout and history")
@SecurityRequirement(name = "bearerAuth")
public class OrderController {

    private final OrderApplicationService orderService;

    @PostMapping
    @PreAuthorize("hasRole('ADMIN') or #request.userId().toString() == authentication.name")
    @Operation(summary = "Create an order and begin payment processing")
    public ResponseEntity<OrderDtos.OrderResponse> create(
            @Valid @RequestBody OrderDtos.CreateOrderRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(orderService.create(request));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get an order")
    public ResponseEntity<OrderDtos.OrderResponse> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(orderService.getById(id));
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN') or #userId.toString() == authentication.name")
    @Operation(summary = "List a user's orders")
    public ResponseEntity<Page<OrderDtos.OrderResponse>> listByUser(
            @RequestParam UUID userId,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(orderService.listByUser(userId, pageable));
    }
}
