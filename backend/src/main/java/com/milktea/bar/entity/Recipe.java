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
import jakarta.persistence.Transient;
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

    /**
     * 售价：规范口径为两位小数（写入时经 PriceSupport 校验并规范化）。
     * 列宽放到 DECIMAL(12,4) 且允许 NULL，只为兼容历史遗留的多位小数/空值数据；
     * 读取时合法值规范化为两位小数返回，异常值标记 priceStatus=「异常」降级展示。
     */
    @Column(precision = 12, scale = 4)
    public BigDecimal price;

    /** 售价状态：正常 / 异常（读取时由服务端判定填充，不持久化） */
    @Transient
    public String priceStatus;

    /** 售价异常说明（priceStatus=「异常」时给出，含原始值），不持久化 */
    @Transient
    public String priceIssue;

    @ElementCollection(fetch = jakarta.persistence.FetchType.EAGER)
    @CollectionTable(name = "recipe_topping", joinColumns = @JoinColumn(name = "recipe_id"))
    @MapKeyColumn(name = "topping_name")
    @Column(name = "qty")
    public Map<String, Integer> toppings = new HashMap<>();

    @Column(name = "created_at")
    public LocalDateTime createdAt;
}
