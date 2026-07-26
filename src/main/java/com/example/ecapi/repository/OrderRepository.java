package com.example.ecapi.repository;

import com.example.ecapi.domain.Order;
import com.example.ecapi.domain.OrderStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<Order, Long> {
    Page<Order> findByUserId(Long userId, Pageable pageable);

    Optional<Order> findByIdAndUserId(Long id, Long userId);

    /** Guest order lookup: the token stands in for authentication. */
    Optional<Order> findByIdAndOrderToken(Long id, String orderToken);

    /** Orders in a given status placed before a cut-off — used to expire stale holds. */
    List<Order> findByStatusAndCreatedAtBefore(OrderStatus status, Instant cutoff);
}
