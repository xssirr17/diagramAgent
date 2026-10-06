package com.example.orderservice;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Component
public class PaymentClient {

    private final RestTemplate restTemplate;

    public PaymentClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public boolean processPayment(String orderId, double amount) {
        return Boolean.TRUE.equals(restTemplate.postForObject("/payments", amount, Boolean.class));
    }
}
