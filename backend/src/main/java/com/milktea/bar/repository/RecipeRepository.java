package com.milktea.bar.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.milktea.bar.entity.Recipe;

import jakarta.persistence.LockModeType;

public interface RecipeRepository extends JpaRepository<Recipe, Long> {

    boolean existsByName(String name);

    boolean existsByNameAndIdNot(String name, Long id);

    /**
     * 对单个配方行加写锁（SELECT ... FOR UPDATE）。
     * 编辑配方时在锁点最新数据上校验与落库，快速连续/并发修改彼此串行，
     * 不会出现过期的校验结论或丢失更新。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM Recipe r WHERE r.id = :id")
    Optional<Recipe> findByIdForUpdate(@Param("id") Long id);
}
