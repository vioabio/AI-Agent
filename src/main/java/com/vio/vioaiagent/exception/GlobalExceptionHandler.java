package com.vio.vioaiagent.exception;

import cn.hutool.core.exceptions.ExceptionUtil;
import com.vio.vioaiagent.common.Result;
import com.vio.vioaiagent.common.ResultCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

/**
 * 全局异常处理器
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // ==================== 业务异常 ====================

    @ExceptionHandler(BusinessException.class)
    public Result<Void> handleBusinessException(BusinessException e, HttpServletRequest request) {
        log.warn("[业务异常] URI: {}, code: {}, message: {}", request.getRequestURI(), e.getCode(), e.getMessage());
        return Result.error(e.getCode(), e.getMessage());
    }

    // ==================== 参数校验异常 ====================

    /**
     * @Validated 校验异常 (请求体)
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleMethodArgumentNotValid(MethodArgumentNotValidException e, HttpServletRequest request) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining(", "));
        log.warn("[参数校验失败] URI: {}, {}", request.getRequestURI(), message);
        return Result.error(ResultCode.PARAM_ERROR, message);
    }

    /**
     * @Validated 校验异常 (表单绑定)
     */
    @ExceptionHandler(BindException.class)
    public Result<Void> handleBindException(BindException e, HttpServletRequest request) {
        String message = e.getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining(", "));
        log.warn("[参数绑定失败] URI: {}, {}", request.getRequestURI(), message);
        return Result.error(ResultCode.PARAM_ERROR, message);
    }

    /**
     * 单个参数校验异常
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public Result<Void> handleConstraintViolation(ConstraintViolationException e, HttpServletRequest request) {
        String message = e.getConstraintViolations().stream()
                .map(ConstraintViolation::getMessage)
                .collect(Collectors.joining(", "));
        log.warn("[约束校验失败] URI: {}, {}", request.getRequestURI(), message);
        return Result.error(ResultCode.PARAM_ERROR, message);
    }

    // ==================== 请求异常 ====================

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public Result<Void> handleMissingParam(MissingServletRequestParameterException e, HttpServletRequest request) {
        log.warn("[缺少参数] URI: {}, param: {}", request.getRequestURI(), e.getParameterName());
        return Result.error(ResultCode.PARAM_ERROR, "缺少必要参数: " + e.getParameterName());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public Result<Void> handleTypeMismatch(MethodArgumentTypeMismatchException e, HttpServletRequest request) {
        log.warn("[参数类型不匹配] URI: {}, param: {}", request.getRequestURI(), e.getName());
        return Result.error(ResultCode.PARAM_ERROR, "参数类型错误: " + e.getName());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result<Void> handleMessageNotReadable(HttpMessageNotReadableException e, HttpServletRequest request) {
        log.warn("[请求体不可读] URI: {}", request.getRequestURI());
        return Result.error(ResultCode.BAD_REQUEST, "请求体格式错误或为空");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public Result<Void> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException e, HttpServletRequest request) {
        log.warn("[不支持的媒体类型] URI: {}", request.getRequestURI());
        return Result.error(ResultCode.BAD_REQUEST, "不支持的 Content-Type");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public Result<Void> handleMethodNotSupported(HttpRequestMethodNotSupportedException e, HttpServletRequest request) {
        log.warn("[不支持的请求方法] URI: {}, method: {}", request.getRequestURI(), e.getMethod());
        return Result.error(ResultCode.METHOD_NOT_ALLOWED, "不支持的请求方法: " + e.getMethod());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public Result<Void> handleMaxUploadSizeExceeded(MaxUploadSizeExceededException e, HttpServletRequest request) {
        log.warn("[上传文件过大] URI: {}", request.getRequestURI());
        return Result.error(ResultCode.BAD_REQUEST, "上传文件大小超出限制");
    }

    // ==================== 资源未找到 ====================

    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Result<Void> handleNotFound(Exception e, HttpServletRequest request) {
        log.warn("[资源未找到] URI: {}", request.getRequestURI());
        return Result.error(ResultCode.NOT_FOUND, "请求的资源不存在");
    }

    // ==================== 系统异常（兜底） ====================

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> handleException(Exception e, HttpServletRequest request) {
        log.error("[系统异常] URI: {} | {}", request.getRequestURI(), ExceptionUtil.stacktraceToString(e));
        return Result.error(ResultCode.INTERNAL_ERROR);
    }
}