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
import jakarta.persistence.Version;

/**
 * 配方：茶底 + 售价 + 小料用量 Map（key=小料名, value=份数），
 * 小料用量通过 @ElementCollection + @CollectionTable + @MapKeyColumn 承载。
 *
 * <p>金额双列口径：</p>
 * <ul>
 *   <li>{@link #price}：规范售价 {@code DECIMAL(10,2)}，新写入的数据一律两位小数；
 *       历史遗留的空价格 / 多位小数 / 非正数无法规范化时该列为 {@code NULL}；</li>
 *   <li>{@link #priceRaw}：历史原始值 {@code DECIMAL(12,4)}，仅用于旧数据回显与降级提示，
 *       不参与任何金额计算；新写入时与规范值相同。</li>
 * </ul>
 * 读取方永远不应静默四舍五入：规范列为 NULL 即「待修复降级态」，必须给出明确状态。
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
     * 规范售价（两位小数）。历史遗留空/多位小数/非正价格无法规范化时为 NULL，
     * 此时必须以 {@link #priceRaw} 给出降级提示，不参与出杯金额计算。
     */
    @Column(nullable = true, precision = 10, scale = 2)
    public BigDecimal price;

    /** 历史原始售价（最多 4 位小数），仅供旧数据回显；新写入与 price 相同。 */
    @Column(name = "price_raw", nullable = true, precision = 12, scale = 4)
    public BigDecimal priceRaw;

    @ElementCollection(fetch = jakarta.persistence.FetchType.EAGER)
    @CollectionTable(name = "recipe_topping", joinColumns = @JoinColumn(name = "recipe_id"))
    @MapKeyColumn(name = "topping_name")
    @Column(name = "qty")
    public Map<String, Integer> toppings = new HashMap<>();

    /** 乐观锁版本：多个页面交替编辑同一配方时，过期提交以最新版本校验后稳定拒绝 */
    @Version
    @Column(nullable = false)
    public Integer version;

    @Column(name = "created_at")
    public LocalDateTime createdAt;
}
