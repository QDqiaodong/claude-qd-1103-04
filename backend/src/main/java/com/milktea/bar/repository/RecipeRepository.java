package com.milktea.bar.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.milktea.bar.entity.Recipe;

public interface RecipeRepository extends JpaRepository<Recipe, Long> {

    boolean existsByName(String name);

    boolean existsByNameAndIdNot(String name, Long id);
}
