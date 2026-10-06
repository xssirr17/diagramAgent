package com.example.diagramagent.api;

public class InsufficientInformationException extends RuntimeException {
    public InsufficientInformationException(String message) {
        super(message);
    }
}
