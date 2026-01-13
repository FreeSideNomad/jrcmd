package com.ivamare.commandbus.e2e.controller;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestMapping;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Custom error controller that handles all errors including view rendering failures.
 * This catches errors that bypass @ControllerAdvice (like template parsing errors).
 */
@Controller
public class CustomErrorController implements ErrorController {

    private static final Logger log = LoggerFactory.getLogger(CustomErrorController.class);
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    @RequestMapping("/error")
    public String handleError(HttpServletRequest request, Model model) {
        Object status = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        Throwable exception = (Throwable) request.getAttribute(RequestDispatcher.ERROR_EXCEPTION);
        String requestUri = (String) request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        String errorMessage = (String) request.getAttribute(RequestDispatcher.ERROR_MESSAGE);

        int statusCode = status != null ? Integer.parseInt(status.toString()) : 500;
        HttpStatus httpStatus = HttpStatus.resolve(statusCode);

        log.error("Error {} at {}: {}", statusCode, requestUri,
            exception != null ? exception.getMessage() : errorMessage, exception);

        model.addAttribute("timestamp", TIMESTAMP_FORMAT.format(Instant.now()));
        model.addAttribute("status", statusCode);
        model.addAttribute("statusReason", httpStatus != null ? httpStatus.getReasonPhrase() : "Error");
        model.addAttribute("path", requestUri != null ? requestUri : request.getRequestURI());
        model.addAttribute("method", request.getMethod());
        model.addAttribute("queryString", request.getQueryString());

        if (exception != null) {
            model.addAttribute("exceptionType", exception.getClass().getName());
            model.addAttribute("exceptionMessage", exception.getMessage());
            model.addAttribute("stackTrace", getStackTrace(exception));

            Throwable rootCause = getRootCause(exception);
            model.addAttribute("rootCauseType", rootCause.getClass().getName());
            model.addAttribute("rootCauseMessage", rootCause.getMessage());
        } else {
            model.addAttribute("exceptionType", "Unknown");
            model.addAttribute("exceptionMessage", errorMessage != null ? errorMessage : "No details available");
            model.addAttribute("stackTrace", "No stack trace available");
            model.addAttribute("rootCauseType", "Unknown");
            model.addAttribute("rootCauseMessage", errorMessage != null ? errorMessage : "No details available");
        }

        // Use a simple error template that doesn't depend on layout
        return "error-standalone";
    }

    private String getStackTrace(Throwable ex) {
        StringWriter sw = new StringWriter();
        ex.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    private Throwable getRootCause(Throwable ex) {
        Throwable cause = ex;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }
}
