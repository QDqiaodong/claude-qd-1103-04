package com.milktea.bar.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.milktea.bar.common.BizException;
import com.milktea.bar.common.Dict;
import com.milktea.bar.common.PriceSupport;
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

    /**
     * 列表：合法售价统一规范化为两位小数返回；历史遗留的多位小数/空值
     * 不静默四舍五入，标记 priceStatus=「异常」并附原始值说明（降级状态）。
     */
    public List<Recipe> list() {
        List<Recipe> all = recipeRepository.findAll();
        all.forEach(RecipeService::applyPriceReadState);
        return all;
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
        // 统一金额口径：空/零/负数/超两位小数/超上限都在这里得到稳定提示
        BigDecimal price = PriceSupport.requireValidForWrite(input.price);
        if (input.toppings == null || input.toppings.isEmpty()) {
            throw new BizException("配方至少需要一种小料");
        }
        validateToppings(input.toppings);

        Recipe saved = new Recipe();
        saved.name = input.name;
        saved.baseTea = input.baseTea;
        saved.price = price;
        saved.toppings = new java.util.HashMap<>(input.toppings);
        saved.createdAt = LocalDateTime.now();
        Recipe persisted = recipeRepository.save(saved);
        applyPriceReadState(persisted);
        return persisted;
    }

    /**
     * 编辑：先对配方行加写锁，在锁点最新数据基础上校验与落库，
     * 快速连续/并发修改彼此串行，响应即最终持久化结果。
     */
    @Transactional(rollbackFor = Exception.class)
    public Recipe update(Long id, Recipe input) {
        Recipe existing = recipeRepository.findByIdForUpdate(id)
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
            existing.price = PriceSupport.requireValidForWrite(input.price);
        }
        if (input.toppings != null) {
            validateToppings(input.toppings);
            existing.toppings.clear();
            existing.toppings.putAll(input.toppings);
        }
        Recipe persisted = recipeRepository.save(existing);
        applyPriceReadState(persisted);
        return persisted;
    }

    /**
     * 读取口径：合法售价规范化为 scale=2（与落库值一致）；异常值保留原始值，
     * 置 priceStatus=「异常」+ priceIssue 说明，绝不静默四舍五入。
     */
    static void applyPriceReadState(Recipe recipe) {
        BigDecimal normalized = PriceSupport.normalizeOnRead(recipe.price);
        if (normalized != null) {
            recipe.price = normalized;
            recipe.priceStatus = PriceSupport.STATUS_OK;
            recipe.priceIssue = null;
        } else {
            recipe.priceStatus = PriceSupport.STATUS_ABNORMAL;
            recipe.priceIssue = PriceSupport.issueOf(recipe.price);
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
