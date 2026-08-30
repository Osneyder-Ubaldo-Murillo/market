package com.nexusmarket.domain.exceptions;

/**
 * Excepción base de negocio del dominio. Toda regla de negocio violada
 * debe propagarse como {@link BusinessException} (o una subclase) para que
 * las capas superiores puedan traducirla a respuestas de API adecuadas.
 */
public class BusinessException extends RuntimeException {

    private final String code;

    public BusinessException(String code, String message) {
        super(message);
        this.code = code;
    }

    public BusinessException(String message) {
        this("BUSINESS_ERROR", message);
    }

    public String getCode() {
        return code;
    }
}