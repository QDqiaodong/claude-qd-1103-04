package com.milktea.bar.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.milktea.bar.common.ApiResponse;
import com.milktea.bar.dto.RecipeDto;
import com.milktea.bar.dto.RecipeSaveRequest;
import com.milktea.bar.service.RecipeService;

@RestController
@RequestMapping("/api/recipes")
public class RecipeController {

    private final RecipeService recipeService;

    public RecipeController(RecipeService recipeService) {
        this.recipeService = recipeService;
    }

    @GetMapping
    public ApiResponse<List<RecipeDto>> list() {
        return ApiResponse.success(recipeService.list());
    }

    @GetMapping("/{id}")
    public ApiResponse<RecipeDto> get(@PathVariable Long id) {
        return ApiResponse.success(recipeService.get(id));
    }

    @PostMapping
    public ApiResponse<RecipeDto> create(@RequestBody RecipeSaveRequest input) {
        return ApiResponse.success(recipeService.create(input));
    }

    @PutMapping("/{id}")
    public ApiResponse<RecipeDto> update(@PathVariable Long id, @RequestBody RecipeSaveRequest input) {
        return ApiResponse.success(recipeService.update(id, input));
    }
}
