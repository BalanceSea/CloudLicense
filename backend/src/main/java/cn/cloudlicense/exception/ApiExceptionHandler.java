package cn.cloudlicense.exception;

import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.OffsetDateTime;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiError> handleApiException(ApiException exception) {
        return ResponseEntity.status(exception.status())
                .body(new ApiError(exception.code(), exception.getMessage(), OffsetDateTime.now()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class})
    ResponseEntity<ApiError> handleValidation(Exception exception) {
        return ResponseEntity.badRequest()
                .body(new ApiError("INVALID_REQUEST", "请求参数不合法", OffsetDateTime.now()));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> handleUnexpected(Exception exception) {
        log.error("Unhandled API exception", exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiError("INTERNAL_ERROR", "服务器处理请求失败", OffsetDateTime.now()));
    }

    public record ApiError(String code, String message, OffsetDateTime timestamp) {
    }
}
