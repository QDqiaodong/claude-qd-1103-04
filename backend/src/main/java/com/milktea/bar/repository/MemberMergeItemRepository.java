package com.milktea.bar.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.milktea.bar.entity.MemberMergeItem;

public interface MemberMergeItemRepository extends JpaRepository<MemberMergeItem, Long> {

    List<MemberMergeItem> findByRequestIdOrderByIdAsc(Long requestId);
}
