package com.obfuscator.api;

import com.obfuscator.domain.ObfuscationException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps domain rejections onto RFC 9457 problem responses. */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ObfuscationException.class)
    public ProblemDetail handle(ObfuscationException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNPROCESSABLE_ENTITY, e.reason());
        problem.setTitle("Obfuscation request rejected");
        problem.setProperty("errors", Map.of("reason", e.reason()));
        return problem;
    }
}