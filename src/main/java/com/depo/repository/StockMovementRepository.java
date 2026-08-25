package com.depo.repository;

import com.depo.entity.StockMovement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface StockMovementRepository extends JpaRepository<StockMovement, Long> {

    List<StockMovement> findAllByOrderByCreatedAtDesc();

    List<StockMovement> findByProductIdOrderByCreatedAtDesc(Long productId);
}
