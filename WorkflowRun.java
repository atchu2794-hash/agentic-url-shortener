package com.example.agentic_url_shortener.Entity;

import java.time.Instant;
import java.util.*;

public class WorkflowRun { // Holds full state, lineage, approvals, and audit logs for one execution
    final String runId; // Unique workflow run identifier
    final String scenarioType; // GREENFIELD, BROWNFIELD, or AMBIGUOUS
    final String requirementInput; // Raw user requirement
    public final Map<StageId, StageStatus> statuses = new EnumMap<>(StageId.class); // Current status per stage
    public final Map<StageId, StageArtifact> artifacts = new EnumMap<>(StageId.class); // Latest artifact per stage
    public final Map<StageId, StageArtifact> lastSafeSnapshots = new EnumMap<>(StageId.class); // Rollback restore points
    public final Set<StageId> highImpactStages = EnumSet.of(StageId.ARCHITECTURE, StageId.RELEASE_READINESS); // Stages needing human sign-off
    public final Set<StageId> approvedStages = EnumSet.noneOf(StageId.class); // Stages already approved by a human
    public final List<DecisionRecord> decisions = new ArrayList<>(); // Cross-stage decision lineage
    final List<AuditEvent> auditLog = new ArrayList<>(); // Audit-grade event stream
    public final Instant startedAt; // Run start timestamp
    public Instant completedAt; // Run completion timestamp

    public WorkflowRun(String runId, String scenarioType, String requirementInput) { // Initializes new run
        this.runId = runId; // Assign run ID
        this.scenarioType = scenarioType; // Assign scenario classification
        this.requirementInput = requirementInput; // Store original requirement
        this.startedAt = Instant.now(); // Capture start time
        for (StageId s : StageId.values()) { // Initialize every stage as PENDING
            statuses.put(s, StageStatus.PENDING); // Default status
        }
    }

    public void audit(StageId stage, String eventType, String detail) { // Appends immutable audit event
        auditLog.add(new AuditEvent(Instant.now(), runId, stage, eventType, detail)); // Record event
    }
}
