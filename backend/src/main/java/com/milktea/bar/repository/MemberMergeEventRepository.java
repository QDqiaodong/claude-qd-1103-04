package com.milktea.bar.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.milktea.bar.entity.MemberMergeEvent;

public interface MemberMergeEventRepository extends JpaRepository<MemberMergeEvent, Long> {

    List<MemberMergeEvent> findByRequestIdOrderByIdAsc(Long requestId);
}
