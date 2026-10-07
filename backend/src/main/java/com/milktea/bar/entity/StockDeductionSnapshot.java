package com.milktea.bar.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * 原料扣减快照：出杯成功时按当次配方实际用量逐行保存。
 * 快照独立于配方表，之后配方被编辑、原料改名都不改变历史消耗；
 * 退款时严格按本快照精确回补，refund_qty 为 NULL 表示尚未回补。
 */
@Entity
@Table(name = "cup_order_stock_snapshot")
public class StockDeductionSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    /** 下单时原料 id（可追溯） */
    @Column(name = "ingredient_id", nullable = false)
    public Long ingredientId;

    /** 下单时原料名（快照，不随后续改名变化） */
    @Column(name = "ingredient_name", nullable = false, length = 64)
    public String ingredientName;

    /** 下单时单位（快照） */
    @Column(nullable = false, length = 16)
    public String unit;

    /** 本次实际扣减量 */
    @Column(name = "used_qty", nullable = false)
    public Integer usedQty;

    /** 退款已回补量；未退款为 NULL */
    @Column(name = "refund_qty")
    public Integer refundQty;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    public CupOrder order;
}
