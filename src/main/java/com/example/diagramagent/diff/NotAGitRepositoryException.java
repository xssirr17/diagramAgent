package com.example.diagramagent.diff;

public class NotAGitRepositoryException extends RuntimeException {
    public NotAGitRepositoryException(String message) {
        super(message);
    }
}
