package com.fandazi.seed;
import org.springframework.data.jpa.repository.JpaRepository;
public interface SeedDishMetadataRepository extends JpaRepository<SeedDishMetadataEntity, Long> { java.util.Optional<SeedDishMetadataEntity> findByDishId(Long dishId); }
