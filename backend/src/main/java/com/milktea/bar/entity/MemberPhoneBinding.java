package com.milktea.bar.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 手机号有效绑定：一个手机号同一时刻只能指向一个「有效」会员。
 *
 * <p>主键即手机号，服务端在事务内对绑定行加悲观写锁（不存在则对潜在键加锁），
 * 并发改手机号/合并在此串行化，后到事务按最新归属稳定拒绝或改指。
 * 合并时副档手机号改指主档；撤销合并时按合并前快照指回。</p>
 */
@Entity
@Table(name = "member_phone_binding")
public class MemberPhoneBinding {

    @Id
    @Column(name = "phone", length = 24)
    public String phone;

    @Column(name = "member_id", nullable = false)
    public Long memberId;

    @Column(name = "bound_at")
    public LocalDateTime boundAt;
}
