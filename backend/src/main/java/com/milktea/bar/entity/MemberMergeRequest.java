package com.milktea.bar.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * 会员合并申请（合并单）。
 *
 * <p>状态机：待处理 →（执行前检查不通过）冲突待处理 →（解决后重新检查通过）已合并；
 * 已合并 →（仅负责人）已撤销。执行与撤销均对合并单行加悲观写锁并配合会员行锁，
 * 重复合并、并发合并、重复撤销在状态层面得到稳定拒绝；{@code version} 提供乐观兜底。</p>
 */
@Entity
@Table(name = "member_merge_request")
public class MemberMergeRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    /** 待处理 / 冲突待处理 / 已合并 / 已撤销 */
    @Column(nullable = false, length = 16)
    public String status;

    @Column(nullable = false, length = 255)
    public String reason;

    @Column(name = "created_by", nullable = false, length = 32)
    public String createdBy;

    @Column(name = "master_member_id", nullable = false)
    public Long masterMemberId;

    /** 冲突类型：手机号冲突 / 积分来源冲突（可并列） */
    @Column(name = "conflict_type", length = 64)
    public String conflictType;

    @Column(name = "conflict_detail", length = 500)
    public String conflictDetail;

    @Column(name = "executed_by", length = 32)
    public String executedBy;

    @Column(name = "executed_at")
    public LocalDateTime executedAt;

    @Column(name = "revoked_by", length = 32)
    public String revokedBy;

    @Column(name = "revoked_at")
    public LocalDateTime revokedAt;

    @Column(name = "revoke_reason", length = 255)
    public String revokeReason;

    @Column(name = "resolved_at")
    public LocalDateTime resolvedAt;

    @Version
    @Column(nullable = false)
    public Integer version;

    @Column(name = "created_at")
    public LocalDateTime createdAt;
}
