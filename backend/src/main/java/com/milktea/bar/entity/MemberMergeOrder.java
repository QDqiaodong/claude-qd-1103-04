package com.milktea.bar.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 合并订单归属迁移记录：每笔从副档迁到主档的历史订单一行。
 * 合并时「已迁移」，撤销时「已恢复」并把订单挂回原会员 —— 撤销后记录仍保留可追溯。
 */
@Entity
@Table(name = "member_merge_order")
public class MemberMergeOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "request_id", nullable = false)
    public Long requestId;

    @Column(name = "order_id", nullable = false)
    public Long orderId;

    @Column(name = "from_member_id", nullable = false)
    public Long fromMemberId;

    @Column(name = "to_member_id", nullable = false)
    public Long toMemberId;

    /** 已迁移 / 已恢复 */
    @Column(nullable = false, length = 8)
    public String status;

    @Column(name = "migrated_at")
    public LocalDateTime migratedAt;

    @Column(name = "restored_at")
    public LocalDateTime restoredAt;
}
