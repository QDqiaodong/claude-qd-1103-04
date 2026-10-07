package com.milktea.bar.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.milktea.bar.entity.Member;

import jakarta.persistence.LockModeType;

public interface MemberRepository extends JpaRepository<Member, Long> {

    List<Member> findAllByOrderByIdAsc();

    List<Member> findByStatusOrderByIdAsc(String status);

    List<Member> findByMergedToId(Long mergedToId);

    /** 手机号/姓名模糊（列表检索、按旧号/别名追溯主档） */
    @Query("SELECT m FROM Member m WHERE m.phone LIKE %:kw% OR m.name LIKE %:kw% ORDER BY m.id ASC")
    List<Member> search(@Param("kw") String keyword);

    /** 下单加积分/退款回退/合并/改手机号时对会员行加写锁，保证余额与服务端一致 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT m FROM Member m WHERE m.id = :id")
    Member findByIdForUpdate(@Param("id") Long id);
}
