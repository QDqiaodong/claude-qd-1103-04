package com.milktea.bar.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.milktea.bar.common.ApiResponse;
import com.milktea.bar.entity.Ingredient;
import com.milktea.bar.service.IngredientService;

@RestController
@RequestMapping("/api/ingredients")
public class IngredientController {

    private final IngredientService ingredientService;

    public IngredientController(IngredientService ingredientService) {
        this.ingredientService = ingredientService;
    }

    @GetMapping
    public ApiResponse<List<Ingredient>> list(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String status) {
        return ApiResponse.success(ingredientService.list(category, status));
    }

    @PostMapping
    public ApiResponse<Ingredient> create(@RequestBody Ingredient input) {
        return ApiResponse.success(ingredientService.create(input));
    }

    @PutMapping("/{id}")
    public ApiResponse<Ingredient> update(@PathVariable Long id, @RequestBody Ingredient input) {
        return ApiResponse.success(ingredientService.update(id, input));
    }
}
