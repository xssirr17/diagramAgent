package com.example.orderservice;

import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public class OrderRepository {

    public Optional<Order> findById(String id) {
        return Optional.of(new Order(id, 100.0, OrderStatus.NEW));
    }

    public Order save(Order order) {
        return order;
    }
}
