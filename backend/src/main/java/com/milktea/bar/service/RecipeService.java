package com.milktea.bar.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.milktea.bar.common.BizException;
import com.milktea.bar.common.Dict;
import com.milktea.bar.entity.Ingredient;
import com.milktea.bar.entity.Recipe;
import com.milktea.bar.repository.IngredientRepository;
import com.milktea.bar.repository.RecipeRepository;

@Service
public class RecipeService {

    private final RecipeRepository recipeRepository;
    private final IngredientRepository ingredientRepository;

    public RecipeService(RecipeRepository recipeRepository, IngredientRepository ingredientRepository) {
        this.recipeRepository = recipeRepository;
        this.ingredientRepository = ingredientRepository;
    }

    public List<Recipe> list() {
        return recipeRepository.findAll();
    }

    public Recipe create(Recipe input) {
        if (input.name == null || input.name.isBlank()) {
            throw new BizException("配方名称不能为空");
        }
        if (recipeRepository.existsByName(input.name)) {
            throw new BizException("配方名称已存在：" + input.name);
        }
        if (input.baseTea == null || input.baseTea.isBlank()) {
            throw new BizException("茶底不能为空");
        }
        if (input.price == null || input.price.compareTo(Dict.RECIPE_MIN_PRICE) < 0) {
            throw new BizException("配方售价必须大于 0");
        }
        if (input.toppings == null || input.toppings.isEmpty()) {
            throw new BizException("配方至少需要一种小料");
        }
        validateToppings(input.toppings);

        Recipe saved = new Recipe();
        saved.name = input.name;
        saved.baseTea = input.baseTea;
        saved.price = input.price;
        saved.toppings = new java.util.HashMap<>(input.toppings);
        saved.createdAt = LocalDateTime.now();
        return recipeRepository.save(saved);
    }

    public Recipe update(Long id, Recipe input) {
        Recipe existing = recipeRepository.findById(id)
            .orElseThrow(() -> new BizException("配方不存在：" + id));
        if (input.name != null && !input.name.equals(existing.name)) {
            if (recipeRepository.existsByNameAndIdNot(input.name, id)) {
                throw new BizException("配方名称已存在：" + input.name);
            }
            existing.name = input.name;
        }
        if (input.baseTea != null) {
            existing.baseTea = input.baseTea;
        }
        if (input.price != null) {
            if (input.price.compareTo(Dict.RECIPE_MIN_PRICE) < 0) {
                throw new BizException("配方售价必须大于 0");
            }
            existing.price = input.price;
        }
        if (input.toppings != null) {
            validateToppings(input.toppings);
            existing.toppings.clear();
            existing.toppings.putAll(input.toppings);
        }
        return recipeRepository.save(existing);
    }

    /**
     * 校验小料用量：每个小料名必须对应一个存在且「启用」的原料，份数必须为正整数。
     */
    private void validateToppings(Map<String, Integer> toppings) {
        for (Map.Entry<String, Integer> entry : toppings.entrySet()) {
            String toppingName = entry.getKey();
            Integer qty = entry.getValue();
            if (!Dict.isValidToppingQty(qty)) {
                throw new BizException("小料「" + toppingName + "」的份数必须为正整数");
            }
            Ingredient ing = ingredientRepository.findAll().stream()
                .filter(i -> toppingName.equals(i.name)).findFirst().orElse(null);
            if (ing == null) {
                throw new BizException("小料「" + toppingName + "」不存在，无法加入配方");
            }
            if (!"启用".equals(ing.status)) {
                throw new BizException("小料「" + toppingName + "」已停用，无法加入配方");
            }
        }
    }
}
