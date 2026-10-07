package com.milktea.bar.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.hibernate.annotations.Fetch;
import org.hibernate.annotations.FetchMode;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

/**
 * 出杯记录：可关联会员（散客为空），按配方明细汇总金额，
 * 状态机 已出杯 → 已退款。
 *
 * <p>出杯成功的订单同时持有本次原料扣减快照 {@link StockDeductionSnapshot}，
 * 历史旧订单该集合为空（退款时仅退积分、不凭空回补库存）。</p>
 */
@Entity
@Table(name = "cup_order")
public class CupOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "member_id")
    public Long memberId;

    @Column(name = "pay_type", nullable = false, length = 16)
    public String payType;

    @Column(nullable = false, precision = 10, scale = 2)
    public BigDecimal total;

    @Column(nullable = false, length = 16)
    public String status;

    /** 本次出杯为会员带来的积分（退款时按此回退） */
    @Column(name = "points_earned")
    public Integer pointsEarned;

    @Column(name = "created_at")
    public LocalDateTime createdAt;

    /** 两个 EAGER 集合都用 SUBSELECT，避免多个 bag 同时 join 抓取导致行数膨胀 */
    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @Fetch(FetchMode.SUBSELECT)
    @OrderBy("id ASC")
    public List<CupOrderItem> items = new ArrayList<>();

    /** 本次出杯的原料扣减快照；历史无快照的旧订单为空 */
    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @Fetch(FetchMode.SUBSELECT)
    @OrderBy("ingredientName ASC")
    public List<StockDeductionSnapshot> deductions = new ArrayList<>();

    /** 仅本次退款响应携带的操作说明（如旧订单无快照不回补库存），不持久化 */
    @Transient
    public String refundNote;
}
