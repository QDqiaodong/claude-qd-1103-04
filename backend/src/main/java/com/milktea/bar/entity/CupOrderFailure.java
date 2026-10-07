package com.milktea.bar.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 出杯失败记录：任一原料不足、停用或配方引用失效时，整单不进入 cup_order、
 * 不扣库存、不加积分，只在此留下可追溯的失败原因。
 */
@Entity
@Table(name = "cup_order_failure")
public class CupOrderFailure {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "member_id")
    public Long memberId;

    /** 下单时会员描述（散客为“散客”），会员事后改名不影响 */
    @Column(name = "member_label", length = 64)
    public String memberLabel;

    @Column(name = "pay_type", nullable = false, length = 16)
    public String payType;

    /** 请求的配方与杯数，如 招牌珍珠奶茶×2 */
    @Column(name = "request_items", nullable = false, length = 255)
    public String requestItems;

    @Column(nullable = false, length = 500)
    public String reason;

    @Column(name = "created_at")
    public LocalDateTime createdAt;
}
