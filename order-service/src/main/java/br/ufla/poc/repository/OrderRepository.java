package br.ufla.poc.repository;

import br.ufla.poc.domain.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface OrderRepository extends JpaRepository<Order, UUID> {

    List<Order> findByCustomerId(String customerId);

    List<Order> findByStatus(Order.OrderStatus status);

    @Query("SELECT COUNT(o) FROM Order o WHERE o.status = :status")
    long countByStatus(Order.OrderStatus status);
}
