package com.milktea.bar.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.milktea.bar.entity.MemberPointsLedger;

public interface MemberPointsLedgerRepository extends JpaRepository<MemberPointsLedger, Long> {

    List<MemberPointsLedger> findByMemberIdOrderByIdAsc(Long memberId);

    /**
     * 合并：把某张合并单迁移过来、当前挂在主档名下的副档流水整批挂回原归属（撤销合并）。
     * 主档自身流水（originMemberId 为空）不动，期间主档新增流水也不动 —— 不重复计算。
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE MemberPointsLedger l SET l.memberId = :originId "
            + "WHERE l.mergeRequestId = :requestId AND l.originMemberId = :originId "
            + "AND l.memberId = :masterId")
    int restoreToOrigin(@Param("requestId") Long requestId,
                        @Param("originId") Long originId,
                        @Param("masterId") Long masterId);

    /**
     * 合并时流水归属迁移（整批 UPDATE，不复制、不新增，杜绝重复计算）：
     * 副档当前名下的全部流水改挂主档，并写本次 mergeRequestId；
     * origin 保留最初来源（撤销后再合并的旧流水也会随新合并单迁走，不遗漏）。
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE MemberPointsLedger l SET "
            + "l.memberId = :toId, "
            + "l.mergeRequestId = :requestId, "
            + "l.originMemberId = CASE WHEN l.originMemberId IS NULL THEN :fromId ELSE l.originMemberId END "
            + "WHERE l.memberId = :fromId")
    int reassign(@Param("requestId") Long requestId,
                 @Param("fromId") Long fromId,
                 @Param("toId") Long toId);
}
