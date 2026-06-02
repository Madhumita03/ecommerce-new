package com.ecommerce.product.service;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;
@ResponseStatus(HttpStatus.CONFLICT)
public class DuplicateSkuException extends RuntimeException {
    public DuplicateSkuException(String msg) { super(msg); }
}
