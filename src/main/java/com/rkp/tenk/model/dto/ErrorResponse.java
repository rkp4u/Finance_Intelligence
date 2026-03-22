package com.rkp.tenk.model.dto;

import java.time.Instant;

public record ErrorResponse(
        int status,
        String message,
        Instant timestamp,
        String path
) {}
