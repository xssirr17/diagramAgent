package com.example.diagramagent.diff;

public class InvalidGitRefException extends RuntimeException {
    public InvalidGitRefException(String message) {
        super(message);
    }
}
