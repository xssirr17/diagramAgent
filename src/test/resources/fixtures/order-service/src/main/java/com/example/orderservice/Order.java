package com.example.orderservice;

public class Order {
    private String id;
    private double amount;
    private OrderStatus status;

    public Order(String id, double amount, OrderStatus status) {
        this.id = id;
        this.amount = amount;
        this.status = status;
    }

    public String getId() {
        return id;
    }

    public double getAmount() {
        return amount;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public void setStatus(OrderStatus status) {
        this.status = status;
    }
}
