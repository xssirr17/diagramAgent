package com.example.diagramagent.render;

public class MmdcNotAvailableException extends RuntimeException {
    public MmdcNotAvailableException(String message) {
        super(message);
    }

    public MmdcNotAvailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
