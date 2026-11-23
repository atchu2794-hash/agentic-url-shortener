package com.example.agentic_url_shortener.Entity;

import java.time.Instant;

public record AuditEvent( // Immutable observability log entry
                          Instant timestamp,     // Event time
                          String runId,          // Workflow execution ID
                          StageId stage,         // Related stage
                          String eventType,      // e.g., GATE_PASSED, RETRY, HUMAN_APPROVAL_REQUIRED, REPLAN, ROLLBACK
                          String detail          // Diagnostic message
) {}
