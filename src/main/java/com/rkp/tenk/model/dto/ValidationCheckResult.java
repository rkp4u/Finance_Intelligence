package com.rkp.tenk.model.dto;

/**
 * Result of a single financial validation check.
 */
public record ValidationCheckResult(
        String checkName,
        boolean passed,
        String expected,
        String actual,
        String message,
        String severity  // CRITICAL, ERROR, WARNING
) {}
