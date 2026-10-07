package com.milktea.bar.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.milktea.bar.entity.MemberPhoneBinding;

public interface MemberPhoneBindingRepository extends JpaRepository<MemberPhoneBinding, String> {

    /**
     * 对手机号绑定行加悲观写锁：并发改手机号/合并在此串行化，
     * 后到事务读到的一定是最新归属。
     *
     * <p>使用原生 SQL：行不存在时 InnoDB 仍会在主键上加间隙锁（next-key lock），
     * 两笔并发绑定同一新号的事务只有一个能插入成功，另一个随后读到已存在的行。
     * nativeQuery 下无结果返回 null，不抛 EmptyResultException。</p>
     */
    @Query(value = "SELECT * FROM member_phone_binding WHERE phone = :phone FOR UPDATE",
            nativeQuery = true)
    MemberPhoneBinding findByPhoneForUpdate(@Param("phone") String phone);

    /** 不加锁读取（同事务已持锁后的复核/普通只读场景） */
    MemberPhoneBinding findByPhone(String phone);
}
