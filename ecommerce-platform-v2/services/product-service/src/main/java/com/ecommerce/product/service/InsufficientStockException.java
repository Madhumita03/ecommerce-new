package com.ecommerce.product.service;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;
@ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
public class InsufficientStockException extends RuntimeException {
    public InsufficientStockException(String msg) { super(msg); }
}
