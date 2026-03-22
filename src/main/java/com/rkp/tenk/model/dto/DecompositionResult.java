package com.rkp.tenk.model.dto;

import java.util.List;

public record DecompositionResult(
        List<String> queries,
        boolean wasDecomposed,
        long decompositionTimeMs
) {}
