package com.fandazi.feature;
import java.util.Map; import org.springframework.http.*; import org.springframework.web.bind.annotation.*;
@RestControllerAdvice public class DomainExceptionHandler { @ExceptionHandler(DomainException.class) ResponseEntity<Map<String,String>> handle(DomainException exception){return ResponseEntity.badRequest().body(Map.of("error",exception.getMessage()));} }
