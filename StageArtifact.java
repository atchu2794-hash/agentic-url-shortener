package com.example.agentic_url_shortener.Entity;

import java.util.Map;

public record StageArtifact( // Versioned output produced by a stage
                             StageId stage,         // Producing stage
                             int version,           // Increments when stage re-runs or re-plans
                             String summary,        // Human-readable summary of output
                             Map<String, Object> payload // Structured details (endpoints, test pass %, risks)
) {}
