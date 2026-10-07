package com.milktea.bar.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.milktea.bar.entity.MemberMergeRequest;

import jakarta.persistence.LockModeType;

public interface MemberMergeRequestRepository extends JpaRepository<MemberMergeRequest, Long> {

    List<MemberMergeRequest> findAllByOrderByIdDesc();

    List<MemberMergeRequest> findByStatusOrderByIdDesc(String status);

    /**
     * 执行合并/撤销时对合并单行加写锁：重复合并、并发合并、重复撤销
     * 只有一个事务能进入状态分支，其余在最新状态上稳定拒绝。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM MemberMergeRequest r WHERE r.id = :id")
    MemberMergeRequest findByIdForUpdate(@Param("id") Long id);

    /** 是否存在以某会员为主档且尚未终结的合并单（待处理/冲突待处理） */
    long countByMasterMemberIdInAndStatusIn(List<Long> memberIds, List<String> statuses);

    /** 是否存在以某会员为副档（非主档）且尚未终结的合并单（待处理/冲突待处理） */
    @Query("SELECT COUNT(r) FROM MemberMergeRequest r "
            + "WHERE r.masterMemberId <> :memberId AND r.status IN :openStatuses "
            + "AND r.id IN (SELECT i.requestId FROM MemberMergeItem i WHERE i.memberId = :memberId)")
    long countOpenAsAlias(@Param("memberId") Long memberId,
                          @Param("openStatuses") List<String> openStatuses);
}
