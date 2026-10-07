package com.milktea.bar.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.Version;

/**
 * 会员：手机号对“有效”会员唯一（由 {@link MemberPhoneBinding} 维护），
 * 积累积分并按阈值升级等级。
 *
 * <p>合并后副档转为「已合并」只读别名：原手机号、姓名原样保留，
 * 记录合并原因/操作人/时间与主档 id（mergedToId），查询时可解析回主档。
 * {@code version} 为乐观锁，并发改手机号、改积分、合并均以最新版本为准。</p>
 */
@Entity
@Table(name = "member")
public class Member {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(nullable = false, length = 24)
    public String phone;

    @Column(nullable = false, length = 32)
    public String name;

    @Column(nullable = false)
    public Integer points;

    @Column(nullable = false, length = 16)
    public String level;

    /** 有效 / 已合并 */
    @Column(nullable = false, length = 16)
    public String status;

    @Column(name = "merged_to_id")
    public Long mergedToId;

    @Column(name = "merge_reason", length = 255)
    public String mergeReason;

    @Column(name = "merged_by", length = 32)
    public String mergedBy;

    @Column(name = "merged_at")
    public LocalDateTime mergedAt;

    /** JPA 乐观锁版本：并发修改时后到事务以最新版本校验 */
    @Version
    @Column(nullable = false)
    public Integer version;

    @Column(name = "created_at")
    public LocalDateTime createdAt;

    /** 查询辅助：当前查询行是否为已合并别名（不持久化） */
    @Transient
    public Boolean alias;

    /** 查询辅助：当前行或别名解析后对应的主档 id（不持久化） */
    @Transient
    public Long masterId;
}
