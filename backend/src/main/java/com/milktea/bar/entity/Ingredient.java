package com.milktea.bar.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * 原料库存：茶底 / 奶 / 小料 / 糖浆 的库存与启用状态。
 */
@Entity
@Table(name = "ingredient", uniqueConstraints = {
    @UniqueConstraint(name = "uk_ingredient_name", columnNames = {"name"})
})
public class Ingredient {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(nullable = false, length = 64)
    public String name;

    @Column(nullable = false, length = 16)
    public String category;

    @Column(nullable = false)
    public Integer stock;

    @Column(nullable = false, length = 16)
    public String unit;

    @Column(nullable = false, length = 16)
    public String status;

    @Column(name = "created_at")
    public LocalDateTime createdAt;
}
