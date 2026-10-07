package com.milktea.bar.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 联系方式变更审计：会员手机号或姓名每次被修改都保留变更前后值、原因、生效时间与操作人。
 * 合并导致的归属变化用 mergeRequestId 关联（本记录仅记录店员主动改手机号/姓名）。
 */
@Entity
@Table(name = "member_contact_audit")
public class MemberContactAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "member_id", nullable = false)
    public Long memberId;

    /** 手机号 / 姓名 */
    @Column(nullable = false, length = 8)
    public String field;

    @Column(name = "old_value", length = 64)
    public String oldValue;

    @Column(name = "new_value", length = 64)
    public String newValue;

    @Column(nullable = false, length = 255)
    public String reason;

    @Column(name = "effective_at", nullable = false)
    public LocalDateTime effectiveAt;

    @Column(nullable = false, length = 32)
    public String operator;

    @Column(name = "merge_request_id")
    public Long mergeRequestId;

    @Column(name = "created_at")
    public LocalDateTime createdAt;
}
