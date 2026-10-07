package com.milktea.bar.service;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.milktea.bar.entity.CupOrderFailure;
import com.milktea.bar.repository.CupOrderFailureRepository;

/**
 * 出杯失败审计记录器：独立事务（REQUIRES_NEW）写入。
 * 主下单事务因库存/停用/配方失效回滚时，失败记录仍可保留供追溯。
 */
@Service
public class CupOrderFailureRecorder {

    private final CupOrderFailureRepository repository;

    public CupOrderFailureRecorder(CupOrderFailureRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void record(Long memberId, String memberLabel, String payType, String requestItems, String reason) {
        CupOrderFailure failure = new CupOrderFailure();
        failure.memberId = memberId;
        failure.memberLabel = memberLabel;
        failure.payType = payType;
        failure.requestItems = truncate(requestItems, 255);
        failure.reason = truncate(reason, 500);
        failure.createdAt = LocalDateTime.now();
        repository.save(failure);
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() > max ? s.substring(0, max) : s;
    }
}
