package com.example.orderservice;

import org.springframework.stereotype.Service;

@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final PaymentClient paymentClient;

    public OrderService(OrderRepository orderRepository, PaymentClient paymentClient) {
        this.orderRepository = orderRepository;
        this.paymentClient = paymentClient;
    }

    public Order createOrder(double amount) {
        Order order = new Order("ord-123", amount, OrderStatus.NEW);
        return orderRepository.save(order);
    }

    public Order payOrder(String id) {
        Order order = orderRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Order not found"));

        if (order.getStatus() != OrderStatus.NEW) {
            throw new IllegalStateException("Only NEW orders can be paid");
        }

        boolean paid = paymentClient.processPayment(order.getId(), order.getAmount());
        if (paid) {
            order.setStatus(OrderStatus.PAID);
        } else {
            order.setStatus(OrderStatus.CANCELLED);
        }
        return orderRepository.save(order);
    }

    public Order shipOrder(String id) {
        Order order = orderRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Order not found"));

        if (order.getStatus() == OrderStatus.PAID) {
            order.setStatus(OrderStatus.SHIPPED);
            return orderRepository.save(order);
        } else {
            throw new IllegalStateException("Order is not paid");
        }
    }
}
