package com.milktea.bar.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 合并成员快照：创建合并单时记录每个成员当时的手机号、姓名、积分、等级、历史订单数；
 * 合并成功后回填 finalPoints（主档=合计，副档=0）。详情页据此展示合并前后差异。
 */
@Entity
@Table(name = "member_merge_item")
public class MemberMergeItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "request_id", nullable = false)
    public Long requestId;

    @Column(name = "member_id", nullable = false)
    public Long memberId;

    /** 主档 / 副档 */
    @Column(nullable = false, length = 8)
    public String role;

    @Column(name = "snapshot_phone", nullable = false, length = 24)
    public String snapshotPhone;

    @Column(name = "snapshot_name", nullable = false, length = 32)
    public String snapshotName;

    @Column(name = "snapshot_points", nullable = false)
    public Integer snapshotPoints;

    @Column(name = "snapshot_level", nullable = false, length = 16)
    public String snapshotLevel;

    @Column(name = "snapshot_order_count", nullable = false)
    public Integer snapshotOrderCount;

    /** 合并后该归属积分（主档=合计，副档=0；未合并为 NULL） */
    @Column(name = "final_points")
    public Integer finalPoints;
}
