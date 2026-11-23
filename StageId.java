package com.example.agentic_url_shortener.Entity;

public enum StageId { // Ordered SDLC stages coordinated by the orchestrator
    REQUIREMENTS,      // Clarify scope, ambiguity, and acceptance criteria
    ARCHITECTURE,      // Define APIs, schema, cache/DB flow, and trade-offs
    IMPLEMENTATION,    // Generate/update application code
    TESTING,           // Run unit/integration/contract checks
    DOCUMENTATION,     // Generate API docs, runbooks, and ADRs
    RELEASE_READINESS  // Final compliance, risk, and rollout gate
}
