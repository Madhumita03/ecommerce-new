package com.ecommerce.urlshortener.service;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;
@ResponseStatus(HttpStatus.NOT_FOUND)
public class UrlNotFoundException extends RuntimeException {
    public UrlNotFoundException(String msg) { super(msg); }
}
