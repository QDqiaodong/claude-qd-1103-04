package com.milktea.bar.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.milktea.bar.entity.Ingredient;

import jakarta.persistence.LockModeType;

public interface IngredientRepository extends JpaRepository<Ingredient, Long> {

    boolean existsByName(String name);

    boolean existsByNameAndIdNot(String name, Long id);

    List<Ingredient> findByCategoryAndStatus(String category, String status);

    List<Ingredient> findByNameIn(Collection<String> names);

    /**
     * 只投影原料 id（结果不进入 Hibernate 一级缓存）。
     * 下单时先取 id、按 id 升序逐个走 {@link #findByIdForUpdate} 锁定加载，
     * 避免“先非锁读把旧值放进一级缓存、再加锁也刷不出最新库存”导致的丢失更新。
     */
    @Query("SELECT i.id FROM Ingredient i WHERE i.name IN :names")
    List<Long> findIdsByNameIn(@Param("names") Collection<String> names);

    /**
     * 对单个原料行加写锁（SELECT ... FOR UPDATE）。
     * 必须是该实体在当前持久化上下文中的首次加载，才能拿到锁点的最新库存。
     * 下单与退款都按原料 id 升序逐个调用，保证并发争用时加锁顺序一致。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM Ingredient i WHERE i.id = :id")
    Optional<Ingredient> findByIdForUpdate(@Param("id") Long id);
}
