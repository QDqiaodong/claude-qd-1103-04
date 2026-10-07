package com.milktea.bar.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;

import com.milktea.bar.common.BizException;
import com.milktea.bar.common.Dict;
import com.milktea.bar.entity.Ingredient;
import com.milktea.bar.repository.IngredientRepository;

@Service
public class IngredientService {

    private final IngredientRepository ingredientRepository;

    public IngredientService(IngredientRepository ingredientRepository) {
        this.ingredientRepository = ingredientRepository;
    }

    public List<Ingredient> list(String category, String status) {
        if (category != null && status != null) {
            return ingredientRepository.findByCategoryAndStatus(category, status);
        }
        if (category != null) {
            return ingredientRepository.findAll().stream()
                .filter(i -> category.equals(i.category)).toList();
        }
        if (status != null) {
            return ingredientRepository.findAll().stream()
                .filter(i -> status.equals(i.status)).toList();
        }
        return ingredientRepository.findAll();
    }

    public Ingredient create(Ingredient input) {
        if (input.name == null || input.name.isBlank()) {
            throw new BizException("原料名称不能为空");
        }
        if (ingredientRepository.existsByName(input.name)) {
            throw new BizException("原料名称已存在：" + input.name);
        }
        if (!Dict.isValidIngredientCategory(input.category)) {
            throw new BizException("原料类别必须是：茶底 / 奶 / 小料 / 糖浆");
        }
        if (input.stock == null || input.stock < 0) {
            throw new BizException("库存不能为负");
        }
        if (input.unit == null || input.unit.isBlank()) {
            throw new BizException("单位不能为空");
        }
        Ingredient saved = new Ingredient();
        saved.name = input.name;
        saved.category = input.category;
        saved.stock = input.stock;
        saved.unit = input.unit;
        saved.status = (input.status == null) ? "启用" : input.status;
        if (!Dict.isValidIngredientStatus(saved.status)) {
            throw new BizException("原料状态必须是：启用 / 停用");
        }
        saved.createdAt = LocalDateTime.now();
        return ingredientRepository.save(saved);
    }

    public Ingredient update(Long id, Ingredient input) {
        Ingredient existing = ingredientRepository.findById(id)
            .orElseThrow(() -> new BizException("原料不存在：" + id));
        if (input.name != null && !input.name.equals(existing.name)) {
            if (ingredientRepository.existsByNameAndIdNot(input.name, id)) {
                throw new BizException("原料名称已存在：" + input.name);
            }
            existing.name = input.name;
        }
        if (input.category != null) {
            if (!Dict.isValidIngredientCategory(input.category)) {
                throw new BizException("原料类别必须是：茶底 / 奶 / 小料 / 糖浆");
            }
            existing.category = input.category;
        }
        if (input.stock != null) {
            if (input.stock < 0) {
                throw new BizException("库存不能为负");
            }
            existing.stock = input.stock;
        }
        if (input.unit != null) {
            existing.unit = input.unit;
        }
        if (input.status != null) {
            if (!Dict.isValidIngredientStatus(input.status)) {
                throw new BizException("原料状态必须是：启用 / 停用");
            }
            existing.status = input.status;
        }
        return ingredientRepository.save(existing);
    }
}
