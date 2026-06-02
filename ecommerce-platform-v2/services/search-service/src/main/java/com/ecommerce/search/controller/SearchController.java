package com.ecommerce.search.controller;

import com.ecommerce.search.service.AutocompleteService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController @RequestMapping("/search")
@RequiredArgsConstructor
@Tag(name = "Search", description = "Autocomplete and full-text search")
public class SearchController {

    private final AutocompleteService autocompleteService;

    @GetMapping("/autocomplete")
    @Operation(summary = "Autocomplete suggestions (Redis Sorted Sets, O(log N))")
    public ResponseEntity<List<String>> autocomplete(@RequestParam String q) {
        if (q.length() < 2) return ResponseEntity.ok(List.of());
        return ResponseEntity.ok(autocompleteService.suggest(q));
    }
}
