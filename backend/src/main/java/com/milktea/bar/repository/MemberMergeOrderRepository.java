package com.milktea.bar.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.milktea.bar.entity.MemberMergeOrder;

public interface MemberMergeOrderRepository extends JpaRepository<MemberMergeOrder, Long> {

    List<MemberMergeOrder> findByRequestIdOrderByIdAsc(Long requestId);

    /** 合并：把副档的历史订单整批改挂主档 */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE CupOrder o SET o.memberId = :toId "
            + "WHERE o.memberId = :fromId")
    int moveOrders(@Param("fromId") Long fromId, @Param("toId") Long toId);

    /** 撤销：把本合并单迁移过的订单按记录逐笔挂回原会员 */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE CupOrder o SET o.memberId = :fromId "
            + "WHERE o.id = :orderId AND o.memberId = :toId")
    int restoreOrder(@Param("orderId") Long orderId,
                     @Param("fromId") Long fromId,
                     @Param("toId") Long toId);
}
