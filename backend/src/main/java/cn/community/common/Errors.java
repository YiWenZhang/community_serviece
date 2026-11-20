package cn.community.common;

import org.slf4j.MDC;
import org.springframework.dao.*;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class Errors {
  @ExceptionHandler(Problem.class)
  ResponseEntity<?> problem(Problem e) {
    return error(e.status, e.getMessage());
  }

  @ExceptionHandler({
    MethodArgumentNotValidException.class,
    HttpMessageNotReadableException.class,
    IllegalArgumentException.class,
    java.time.DateTimeException.class,
    org.springframework.web.bind.MissingRequestHeaderException.class,
    org.springframework.web.bind.MissingServletRequestParameterException.class,
    org.springframework.web.multipart.support.MissingServletRequestPartException.class,
    org.springframework.beans.TypeMismatchException.class
  })
  ResponseEntity<?> invalid(Exception e) {
    return error(400, "请求格式或字段校验失败，请检查接口文档");
  }

  @ExceptionHandler({
    org.springframework.web.servlet.resource.NoResourceFoundException.class,
    org.springframework.web.servlet.NoHandlerFoundException.class
  })
  ResponseEntity<?> missing(Exception e) {
    return error(404, "资源不存在");
  }

  @ExceptionHandler(DuplicateKeyException.class)
  ResponseEntity<?> duplicate(Exception e) {
    return error(409, "业务编号、版本或幂等键重复");
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  ResponseEntity<?> integrity(Exception e) {
    return error(422, "关联资源或数据约束不满足");
  }

  @ExceptionHandler(AccessDeniedException.class)
  ResponseEntity<?> denied(Exception e) {
    return error(403, "没有操作权限");
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<?> unknown(Exception e) {
    org.slf4j.LoggerFactory.getLogger(Errors.class)
        .error("request failed trace={} type={}", MDC.get("traceId"), e.getClass().getSimpleName());
    return error(500, "服务暂时不可用，请使用traceId联系管理员");
  }

  static ResponseEntity<?> error(int code, String message) {
    return ResponseEntity.status(code)
        .body(Db.p("code", code, "message", message, "traceId", MDC.get("traceId")));
  }
}
