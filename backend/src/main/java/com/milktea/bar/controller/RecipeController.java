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
import com.milktea.bar.entity.Recipe;
import com.milktea.bar.service.RecipeService;

@RestController
@RequestMapping("/api/recipes")
public class RecipeController {

    private final RecipeService recipeService;

    public RecipeController(RecipeService recipeService) {
        this.recipeService = recipeService;
    }

    @GetMapping
    public ApiResponse<List<Recipe>> list() {
        return ApiResponse.success(recipeService.list());
    }

    @PostMapping
    public ApiResponse<Recipe> create(@RequestBody Recipe input) {
        return ApiResponse.success(recipeService.create(input));
    }

    @PutMapping("/{id}")
    public ApiResponse<Recipe> update(@PathVariable Long id, @RequestBody Recipe input) {
        return ApiResponse.success(recipeService.update(id, input));
    }
}
