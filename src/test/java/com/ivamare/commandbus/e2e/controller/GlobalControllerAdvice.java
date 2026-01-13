package com.ivamare.commandbus.e2e.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Global controller advice to add common model attributes and handle exceptions.
 * Provides currentPath attribute for navigation highlighting since
 * #request is no longer available by default in Thymeleaf 3.1+.
 */
@ControllerAdvice
public class GlobalControllerAdvice {

    private static final Logger log = LoggerFactory.getLogger(GlobalControllerAdvice.class);

    @ModelAttribute("currentPath")
    public String currentPath(HttpServletRequest request) {
        return request.getRequestURI();
    }

    /**
     * Let Spring handle static resource 404s normally (don't show error page).
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public void handleNoResourceFound(NoResourceFoundException ex) throws NoResourceFoundException {
        throw ex;  // Re-throw to let Spring's default handling take over
    }

    /**
     * Handle all exceptions and display detailed error page.
     * This is a dev/test UI so we expose full exception details.
     */
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    @ExceptionHandler(Exception.class)
    public ModelAndView handleException(HttpServletRequest request, Exception ex) {
        log.error("Unhandled exception at {}: {}", request.getRequestURI(), ex.getMessage(), ex);

        ModelAndView mav = new ModelAndView("pages/error");
        mav.addObject("currentPath", request.getRequestURI());
        mav.addObject("timestamp", TIMESTAMP_FORMAT.format(Instant.now()));
        mav.addObject("path", request.getRequestURI());
        mav.addObject("method", request.getMethod());
        mav.addObject("queryString", request.getQueryString());
        mav.addObject("exceptionType", ex.getClass().getName());
        mav.addObject("exceptionMessage", ex.getMessage());
        mav.addObject("stackTrace", getStackTrace(ex));
        mav.addObject("rootCause", getRootCause(ex));
        mav.addObject("rootCauseMessage", getRootCause(ex).getMessage());
        mav.addObject("rootCauseType", getRootCause(ex).getClass().getName());

        return mav;
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
