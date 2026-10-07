package com.milktea.bar.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.milktea.bar.entity.CupOrderFailure;

public interface CupOrderFailureRepository extends JpaRepository<CupOrderFailure, Long> {

    List<CupOrderFailure> findAllByOrderByCreatedAtDescIdDesc();
}
