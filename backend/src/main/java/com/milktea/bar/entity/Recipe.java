package com.milktea.bar.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * 配方：茶底 + 售价 + 小料用量 Map（key=小料名, value=份数），
 * 小料用量通过 @ElementCollection + @CollectionTable + @MapKeyColumn 承载。
 */
@Entity
@Table(name = "recipe", uniqueConstraints = {
    @UniqueConstraint(name = "uk_recipe_name", columnNames = {"name"})
})
public class Recipe {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(nullable = false, length = 64)
    public String name;

    @Column(name = "base_tea", nullable = false, length = 64)
    public String baseTea;

    @Column(nullable = false, precision = 10, scale = 2)
    public BigDecimal price;

    @ElementCollection(fetch = jakarta.persistence.FetchType.EAGER)
    @CollectionTable(name = "recipe_topping", joinColumns = @JoinColumn(name = "recipe_id"))
    @MapKeyColumn(name = "topping_name")
    @Column(name = "qty")
    public Map<String, Integer> toppings = new HashMap<>();

    @Column(name = "created_at")
    public LocalDateTime createdAt;
}
