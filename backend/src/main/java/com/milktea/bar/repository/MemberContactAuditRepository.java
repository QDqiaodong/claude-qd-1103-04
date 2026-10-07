package com.milktea.bar.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.milktea.bar.entity.MemberContactAudit;

public interface MemberContactAuditRepository extends JpaRepository<MemberContactAudit, Long> {

    List<MemberContactAudit> findByMemberIdOrderByIdDesc(Long memberId);
}
