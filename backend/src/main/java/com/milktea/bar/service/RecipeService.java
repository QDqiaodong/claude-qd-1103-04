package com.milktea.bar.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.milktea.bar.common.BizException;
import com.milktea.bar.common.Dict;
import com.milktea.bar.common.Money;
import com.milktea.bar.dto.RecipeDto;
import com.milktea.bar.dto.RecipeSaveRequest;
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

    public List<RecipeDto> list() {
        return recipeRepository.findAll().stream().map(RecipeDto::of).toList();
    }

    public RecipeDto get(Long id) {
        Recipe recipe = recipeRepository.findById(id)
            .orElseThrow(() -> new BizException("配方不存在：" + id));
        return RecipeDto.of(recipe);
    }

    @Transactional(rollbackFor = Exception.class)
    public RecipeDto create(RecipeSaveRequest input) {
        if (input == null) {
            throw new BizException("配方内容不能为空");
        }
        if (input.name == null || input.name.isBlank()) {
            throw new BizException("配方名称不能为空");
        }
        if (recipeRepository.existsByName(input.name.trim())) {
            throw new BizException("配方名称已存在：" + input.name.trim());
        }
        if (input.baseTea == null || input.baseTea.isBlank()) {
            throw new BizException("茶底不能为空");
        }
        // 售价统一在服务端最新口径上校验：空/0/负/超过两位小数/超上限稳定拒绝
        BigDecimal canonical = Money.validateRecipePrice(input.price);
        if (input.toppings == null || input.toppings.isEmpty()) {
            throw new BizException("配方至少需要一种小料");
        }
        validateToppings(input.toppings);

        Recipe saved = new Recipe();
        saved.name = input.name.trim();
        saved.baseTea = input.baseTea.trim();
        saved.price = canonical;
        saved.priceRaw = canonical;
        saved.toppings = new java.util.HashMap<>(input.toppings);
        saved.createdAt = LocalDateTime.now();
        return RecipeDto.of(recipeRepository.save(saved));
    }

    @Transactional(rollbackFor = Exception.class)
    public RecipeDto update(Long id, RecipeSaveRequest input) {
        if (input == null) {
            throw new BizException("配方内容不能为空");
        }
        Recipe existing = recipeRepository.findById(id)
            .orElseThrow(() -> new BizException("配方不存在：" + id));
        // 以服务端最新版本校验：多个页面交替保存时，过期提交稳定拒绝，不发生后写覆盖先写
        if (input.version != null && existing.version != null
                && !input.version.equals(existing.version)) {
            throw new BizException("配方已被其他页面修改（当前版本 v" + existing.version
                    + "），请重新载入最新配方后再编辑");
        }
        if (input.name != null && !input.name.isBlank()
                && !input.name.trim().equals(existing.name)) {
            if (recipeRepository.existsByNameAndIdNot(input.name.trim(), id)) {
                throw new BizException("配方名称已存在：" + input.name.trim());
            }
            existing.name = input.name.trim();
        }
        if (input.baseTea != null && !input.baseTea.isBlank()) {
            existing.baseTea = input.baseTea.trim();
        }
        if (input.price != null) {
            // 即便是修复历史遗留价格，也必须通过统一校验，绝不接受静默四舍五入
            BigDecimal canonical = Money.validateRecipePrice(input.price);
            existing.price = canonical;
            existing.priceRaw = canonical;
        }
        if (input.toppings != null) {
            if (input.toppings.isEmpty()) {
                throw new BizException("配方至少需要一种小料");
            }
            validateToppings(input.toppings);
            existing.toppings.clear();
            existing.toppings.putAll(input.toppings);
        }
        try {
            return RecipeDto.of(recipeRepository.saveAndFlush(existing));
        } catch (ObjectOptimisticLockingFailureException e) {
            throw new BizException("配方已被其他页面修改，请重新载入最新配方后再编辑");
        }
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
