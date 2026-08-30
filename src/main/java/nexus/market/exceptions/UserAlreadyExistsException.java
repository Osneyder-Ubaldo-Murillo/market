package com.nexusmarket.domain.exceptions;

/**
 * Se lanza cuando el {@code email} o el {@code documentId} de un usuario ya
 * se encuentran registrados en la plataforma (regla de unicidad global).
 */
public class UserAlreadyExistsException extends BusinessException {

    public UserAlreadyExistsException(String message) {
        super("USER_ALREADY_EXISTS", message);
    }
}