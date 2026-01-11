package com.ivamare.commandbus.e2e.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Global controller advice to add common model attributes.
 * Provides currentPath attribute for navigation highlighting since
 * #request is no longer available by default in Thymeleaf 3.1+.
 */
@ControllerAdvice
public class GlobalControllerAdvice {

    @ModelAttribute("currentPath")
    public String currentPath(HttpServletRequest request) {
        return request.getRequestURI();
    }
}
