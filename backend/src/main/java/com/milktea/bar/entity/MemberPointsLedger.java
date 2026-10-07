package com.milktea.bar.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 会员积分流水：每笔积分变动一行，会员余额 = 名下各行 changePoints 之和。
 *
 * <p>合并：副档流水整批改挂主档并记 {@code mergeRequestId} 与 {@code originMemberId}；
 * 撤销：按 origin 整批挂回原归属，期间主档新增流水不动 —— 积分不重复计算。
 * {@code balanceAfter} 是变动当时的余额快照，改挂/撤销只换归属、不回改历史。</p>
 */
@Entity
@Table(name = "member_points_ledger")
public class MemberPointsLedger {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "member_id", nullable = false)
    public Long memberId;

    @Column(name = "change_points", nullable = false)
    public Integer changePoints;

    @Column(name = "balance_after", nullable = false)
    public Integer balanceAfter;

    /** 期初 / 消费获得 / 退款回退 / 手工调整 */
    @Column(nullable = false, length = 16)
    public String type;

    /** 关联类型：订单 / 合并（普通业务流水为空） */
    @Column(name = "ref_type", length = 16)
    public String refType;

    @Column(name = "ref_id")
    public Long refId;

    /** 经由哪张合并单迁移（provenance，撤销据此挂回） */
    @Column(name = "merge_request_id")
    public Long mergeRequestId;

    /** 合并迁移前的原归属会员 */
    @Column(name = "origin_member_id")
    public Long originMemberId;

    @Column(length = 255)
    public String reason;

    @Column(length = 32)
    public String operator;

    @Column(name = "created_at")
    public LocalDateTime createdAt;
}
