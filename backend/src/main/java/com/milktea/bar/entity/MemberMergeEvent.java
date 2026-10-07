package com.milktea.bar.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 合并单生命周期审计事件：创建 / 冲突待处理 / 合并成功 / 撤销合并 / 重新检查。
 * 与成员快照、订单迁移记录共同构成合并审计链路。
 */
@Entity
@Table(name = "member_merge_event")
public class MemberMergeEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "request_id", nullable = false)
    public Long requestId;

    @Column(nullable = false, length = 32)
    public String event;

    @Column(length = 500)
    public String detail;

    @Column(length = 32)
    public String operator;

    @Column(name = "created_at")
    public LocalDateTime createdAt;
}
