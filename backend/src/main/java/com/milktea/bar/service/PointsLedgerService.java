package com.milktea.bar.service;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;

import com.milktea.bar.entity.Member;
import com.milktea.bar.entity.MemberPointsLedger;
import com.milktea.bar.repository.MemberPointsLedgerRepository;

/**
 * 积分流水写入助手：会员余额与流水在同一业务事务内维护，
 * 会员积分恒等于名下流水 {@code change_points} 之和。
 *
 * <p>注意：调用方须持有会员行的悲观写锁；本组件只追加流水、回写余额，
 * 不加 {@code @Transactional}，加入调用方既有事务，保证余额与流水同生共死。</p>
 */
@Service
public class PointsLedgerService {

    private final MemberPointsLedgerRepository ledgerRepository;

    public PointsLedgerService(MemberPointsLedgerRepository ledgerRepository) {
        this.ledgerRepository = ledgerRepository;
    }

    /**
     * 在已锁定的会员上记一笔积分变动并更新余额。
     *
     * @param member 已加行锁的会员
     * @param delta  变动值（正加负减）
     * @param type   流水类型（消费获得 / 退款回退 / 手工调整 等）
     */
    public MemberPointsLedger append(Member member, int delta, String type,
                                     String refType, Long refId,
                                     String reason, String operator) {
        int newBalance = member.points + delta;
        MemberPointsLedger ledger = new MemberPointsLedger();
        ledger.memberId = member.id;
        ledger.changePoints = delta;
        ledger.balanceAfter = newBalance;
        ledger.type = type;
        ledger.refType = refType;
        ledger.refId = refId;
        ledger.reason = reason;
        ledger.operator = operator;
        ledger.createdAt = LocalDateTime.now();
        ledgerRepository.save(ledger);

        member.points = newBalance;
        return ledger;
    }

    /** 汇总会员名下流水（合并前检查积分来源、合并/撤销后重算余额使用） */
    public int sum(Long memberId) {
        return ledgerRepository.findByMemberIdOrderByIdAsc(memberId).stream()
                .mapToInt(l -> l.changePoints)
                .sum();
    }
}
