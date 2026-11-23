package com.example.agentic_url_shortener.Service;

import com.example.agentic_url_shortener.Entity.*;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Future;
import java.util.function.Function;

@Service // Spring service managing SDLC orchestration
class SdLcOrchestrator { // Stateful orchestrator with dependency graph, gates, parallelism, HITL, retries, rollback & re-planning

    private static final int MAX_RETRIES = 2; // Bounded retry cap per stage
    private static final List<String> FORBIDDEN_POLICY_TOKENS = List.of("DROP TABLE", "DISABLE_AUTH", "HARDCODED_SECRET"); // Security/compliance guardrails

    private final Map<StageId, List<StageId>> dependencies = new EnumMap<>(StageId.class); // Explicit DAG edges
    private final Map<String, WorkflowRun> runs = new ConcurrentHashMap<>(); // Active and historical runs
    private final ExecutorService parallelPool = Executors.newFixedThreadPool(3); // Worker pool for parallel stages

    private int totalRetries = 0; // Metric counter for retries
    private int totalRollbacks = 0; // Metric counter for rollbacks
    private long totalRecoveryMs = 0; // Cumulative recovery time for MTTR calculation
    private int recoveryCount = 0; // Number of recovered failures
    private long totalRunLatencyMs = 0; // Cumulative end-to-end latency
    private int completedRunCount = 0; // Number of finished runs

    SdLcOrchestrator() { // Defines explicit dependency graph between SDLC stages
        dependencies.put(StageId.REQUIREMENTS, List.of()); // Entry stage has no prerequisites
        dependencies.put(StageId.ARCHITECTURE, List.of(StageId.REQUIREMENTS)); // Architecture depends on normalized requirements
        dependencies.put(StageId.IMPLEMENTATION, List.of(StageId.ARCHITECTURE)); // Code depends on architecture
        dependencies.put(StageId.DOCUMENTATION, List.of(StageId.ARCHITECTURE)); // Docs can run in parallel with implementation after architecture
        dependencies.put(StageId.TESTING, List.of(StageId.IMPLEMENTATION)); // Tests run after implementation
        dependencies.put(StageId.RELEASE_READINESS, List.of(StageId.TESTING, StageId.DOCUMENTATION)); // Synchronization join gate: needs both tests & docs
    }

    public WorkflowRun startRun(String scenarioType, String requirementInput, Map<StageId, Function<WorkflowRun, StageArtifact>> executors) { // Starts a workflow run
        String runId = "run-" + UUID.randomUUID().toString().substring(0, 8); // Generate short readable run ID
        WorkflowRun run = new WorkflowRun(runId, scenarioType, requirementInput); // Create workflow state container
        runs.put(runId, run); // Register run
        run.audit(StageId.REQUIREMENTS, "RUN_STARTED", "Scenario=" + scenarioType + " | Input=" + requirementInput); // Audit start
        advance(run, executors); // Execute ready stages until completion or human approval pause
        return run; // Return current workflow state
    }

    public WorkflowRun approveStage(String runId, StageId stage, String reviewer, String note, // Human approval checkpoint handler
                                    Map<StageId, Function<WorkflowRun, StageArtifact>> executors) {
        WorkflowRun run = Objects.requireNonNull(runs.get(runId), "Unknown runId: " + runId); // Look up run
        run.approvedStages.add(stage); // Record human approval for this stage
        run.statuses.put(stage, StageStatus.PENDING); // Unblock stage so it can execute
        run.decisions.add(new DecisionRecord(stage, "HUMAN_APPROVED by " + reviewer, note, Instant.now())); // Add to decision lineage
        run.audit(stage, "HUMAN_APPROVED", "Reviewer=" + reviewer + " | Note=" + note); // Audit approval
        advance(run, executors); // Continue orchestration
        return run; // Return updated run
    }

    public WorkflowRun updateUpstreamAndReplan(String runId, StageId changedStage, String newSummary, // Dynamic re-planning when upstream changes
                                               Map<StageId, Function<WorkflowRun, StageArtifact>> executors) {
        WorkflowRun run = Objects.requireNonNull(runs.get(runId), "Unknown runId: " + runId); // Look up run
        int nextVersion = Optional.ofNullable(run.artifacts.get(changedStage)).map(a -> a.version() + 1).orElse(1); // Bump artifact version
        StageArtifact updatedArtifact = new StageArtifact(changedStage, nextVersion, newSummary, Map.of("replanned", true)); // New upstream artifact
        run.artifacts.put(changedStage, updatedArtifact); // Store new artifact
        run.statuses.put(changedStage, StageStatus.COMPLETED); // Mark changed stage completed
        run.decisions.add(new DecisionRecord(changedStage, "UPSTREAM_CHANGE_REPLAN", "Changed " + changedStage + " to v" + nextVersion, Instant.now())); // Record decision
        run.audit(changedStage, "REPLAN_TRIGGERED", "Invalidating downstream stages after " + changedStage + " v" + nextVersion); // Audit re-plan

        for (StageId candidate : StageId.values()) { // Walk stages and invalidate any that transitively depend on changedStage
            if (candidate != changedStage && dependsTransitively(candidate, changedStage)) { // Check dependency path
                run.statuses.put(candidate, StageStatus.INVALIDATED); // Mark stage stale so it re-runs
                run.audit(candidate, "STAGE_INVALIDATED", "Invalidated due to upstream change in " + changedStage); // Audit invalidation
            }
        }
        advance(run, executors); // Re-execute invalidated downstream stages
        return run; // Return updated run
    }

    public synchronized void advance(WorkflowRun run, Map<StageId, Function<WorkflowRun, StageArtifact>> executors) { // Core orchestration loop
        while (true) { // Loop until no more stages can progress right now
            List<StageId> readyStages = findReadyStages(run); // Find stages whose dependencies are satisfied
            if (readyStages.isEmpty()) { // Stop if nothing is ready
                break; // Exit loop
            }

            // Separate stages that can run concurrently (e.g., IMPLEMENTATION and DOCUMENTATION)
            List<StageId> runnableNow = new ArrayList<>(); // Stages ready to execute now
            for (StageId stage : readyStages) { // Check gates and human approval requirements for each ready stage
                if (!checkEntryGate(run, stage)) { // Verify entry gate preconditions
                    run.audit(stage, "ENTRY_GATE_BLOCKED", "Entry gate conditions not met for " + stage); // Audit blocked entry
                    continue; // Skip until prerequisites are satisfied
                }
                if (run.highImpactStages.contains(stage) && !run.approvedStages.contains(stage)) { // Enforce human approval on high-impact stages
                    run.statuses.put(stage, StageStatus.WAITING_HUMAN_APPROVAL); // Pause stage for human review
                    run.audit(stage, "HUMAN_APPROVAL_REQUIRED", "Awaiting human sign-off for high-impact stage " + stage); // Audit pause
                    continue; // Do not auto-execute high-impact stage without approval
                }
                runnableNow.add(stage); // Stage cleared entry gate and approval check
            }

            if (runnableNow.isEmpty()) { // If all ready stages are waiting on human approval or gates
                break; // Pause loop and return state to caller
            }

            if (runnableNow.size() > 1) { // Execute multiple independent ready stages in parallel
                List<Future<?>> futures = new ArrayList<>(); // Track parallel tasks
                for (StageId stage : runnableNow) { // Submit each ready stage to thread pool
                    futures.add(parallelPool.submit(() -> executeStageWithReliability(run, stage, executors.get(stage)))); // Run stage
                }
                for (Future<?> f : futures) { // Synchronization barrier: wait for all parallel branches to finish
                    try {
                        f.get(); // Block until parallel task finishes
                    } catch (InterruptedException e) { // Handle thread interruption
                        Thread.currentThread().interrupt(); // Restore interrupt flag
                        throw new IllegalStateException("Interrupted during parallel stage execution", e); // Surface error
                    } catch (ExecutionException e) { // Handle task failure
                        throw new IllegalStateException("Parallel stage execution failed", e.getCause()); // Surface cause
                    }
                }
            } else { // Single stage ready: execute directly
                executeStageWithReliability(run, runnableNow.get(0), executors.get(runnableNow.get(0))); // Run single stage
            }
        }

        if (allStagesCompleted(run) && run.completedAt == null) { // Check if entire SDLC graph completed
            run.completedAt = Instant.now(); // Record completion timestamp
            long durationMs = Duration.between(run.startedAt, run.completedAt).toMillis(); // Compute run latency
            synchronized (this) { // Update shared metrics safely
                totalRunLatencyMs += durationMs; // Add run duration
                completedRunCount++; // Increment completed runs
            }
            run.audit(StageId.RELEASE_READINESS, "RUN_COMPLETED", "All SDLC stages passed in " + durationMs + " ms"); // Audit completion
        }
    }

    private void executeStageWithReliability(WorkflowRun run, StageId stage, Function<WorkflowRun, StageArtifact> executor) { // Handles retries, policy guardrails, exit gates, and rollback
        Instant failureStart = null; // Tracks when a failure began for MTTR
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) { // Bounded retry loop (initial try + MAX_RETRIES)
            run.statuses.put(stage, StageStatus.RUNNING); // Mark stage as actively running
            run.audit(stage, "STAGE_STARTED", "Attempt " + (attempt + 1) + " for " + stage); // Audit start

            try {
                StageArtifact artifact = executor.apply(run); // Execute stage logic to produce an artifact
                enforcePolicyGuardrails(run, stage, artifact); // Check security, compliance, and change-control policies
                if (!checkExitGate(run, stage, artifact)) { // Validate stage output quality gate
                    throw new IllegalStateException("Exit gate failed for stage " + stage); // Trigger retry/rollback path
                }

                int nextVer = Optional.ofNullable(run.artifacts.get(stage)).map(a -> a.version() + 1).orElse(1); // Version number
                StageArtifact versioned = new StageArtifact(stage, nextVer, artifact.summary(), artifact.payload()); // Create versioned artifact
                run.artifacts.put(stage, versioned); // Save latest artifact
                run.lastSafeSnapshots.put(stage, versioned); // Save rollback snapshot for verified output
                run.statuses.put(stage, StageStatus.COMPLETED); // Mark stage completed
                run.audit(stage, "EXIT_GATE_PASSED", "Completed " + stage + " v" + nextVer); // Audit success

                if (failureStart != null) { // If we recovered from an earlier failure
                    long mttr = Duration.between(failureStart, Instant.now()).toMillis(); // Measure recovery time
                    synchronized (this) { // Update MTTR metric
                        totalRecoveryMs += mttr; // Add recovery duration
                        recoveryCount++; // Increment recovery count
                    }
                }
                return; // Exit retry loop on success
            } catch (Exception ex) { // Catch transient error, policy violation, or gate failure
                if (failureStart == null) { // Capture first failure timestamp
                    failureStart = Instant.now(); // Start MTTR timer
                }
                if (attempt < MAX_RETRIES) { // Still within bounded retry budget
                    synchronized (this) { totalRetries++; } // Increment retry metric
                    run.audit(stage, "RETRY_SCHEDULED", "Attempt " + (attempt + 1) + " failed: " + ex.getMessage()); // Audit retry
                } else { // Retries exhausted: rollback to last safe snapshot and safe-stop stage
                    rollbackStage(run, stage, ex.getMessage()); // Restore last known-good state if available
                    return; // Safe-stop without crashing the entire JVM
                }
            }
        }
    }

    private void enforcePolicyGuardrails(WorkflowRun run, StageId stage, StageArtifact artifact) { // Security, compliance, and change-control guard
        String combinedText = (artifact.summary() + " " + artifact.payload()).toUpperCase(Locale.ROOT); // Normalize text for policy scan
        for (String forbidden : FORBIDDEN_POLICY_TOKENS) { // Check against restricted patterns
            if (combinedText.contains(forbidden)) { // Policy violation detected
                run.audit(stage, "POLICY_VIOLATION", "Blocked forbidden pattern: " + forbidden); // Audit policy block
                throw new SecurityException("Policy guardrail blocked stage " + stage + ": " + forbidden); // Fail stage safely
            }
        }
    }

    private boolean checkEntryGate(WorkflowRun run, StageId stage) { // Verifies upstream prerequisites are completed
        for (StageId dep : dependencies.getOrDefault(stage, List.of())) { // Check each parent stage
            if (run.statuses.get(dep) != StageStatus.COMPLETED) { // Parent must be COMPLETED (not PENDING or INVALIDATED)
                return false; // Entry gate closed
            }
        }
        return true; // All prerequisites satisfied
    }

    private boolean checkExitGate(WorkflowRun run, StageId stage, StageArtifact artifact) { // Verifies stage produced valid output
        if (artifact == null || artifact.summary() == null || artifact.summary().isBlank()) { // Ensure non-empty output summary
            return false; // Exit gate failed
        }
        if (stage == StageId.TESTING) { // Extra quality gate for testing stage
            Object passed = artifact.payload().get("testsPassed"); // Read test status from payload
            return Boolean.TRUE.equals(passed); // Require testsPassed == true before release readiness
        }
        return true; // Default exit gate passed
    }

    private void rollbackStage(WorkflowRun run, StageId stage, String reason) { // Rolls back to last safe snapshot and marks FAILED
        StageArtifact snapshot = run.lastSafeSnapshots.get(stage); // Look up previous verified artifact
        if (snapshot != null) { // Restore previous safe artifact if one exists
            run.artifacts.put(stage, snapshot); // Revert artifact
            synchronized (this) { totalRollbacks++; } // Increment rollback metric
            run.audit(stage, "ROLLBACK_RESTORED", "Restored v" + snapshot.version() + " due to: " + reason); // Audit rollback
        }
        run.statuses.put(stage, StageStatus.FAILED); // Safe-stop stage in FAILED state
        run.audit(stage, "SAFE_STOP", "Stage " + stage + " halted safely: " + reason); // Audit safe stop
    }

    private List<StageId> findReadyStages(WorkflowRun run) { // Finds PENDING or INVALIDATED stages whose dependencies are COMPLETED
        List<StageId> ready = new ArrayList<>(); // Collect ready stages
        for (StageId stage : StageId.values()) { // Inspect all stages in order
            StageStatus status = run.statuses.get(stage); // Current stage status
            if ((status == StageStatus.PENDING || status == StageStatus.INVALIDATED) && checkEntryGate(run, stage)) { // Eligible to run
                ready.add(stage); // Add to ready list
            }
        }
        return ready; // Return ready stages
    }

    private boolean dependsTransitively(StageId child, StageId ancestor) { // Checks if child depends directly or indirectly on ancestor
        for (StageId direct : dependencies.getOrDefault(child, List.of())) { // Inspect direct parents
            if (direct == ancestor || dependsTransitively(direct, ancestor)) { // Direct match or recursive match
                return true; // Dependency exists
            }
        }
        return false; // No dependency path
    }

    private boolean allStagesCompleted(WorkflowRun run) { // Returns true when every stage in the graph is COMPLETED
        return run.statuses.values().stream().allMatch(s -> s == StageStatus.COMPLETED); // Check all statuses
    }

    public synchronized ReliabilityMetrics getMetrics() { // Computes and returns current reliability metrics
        double avgMttr = recoveryCount == 0 ? 0.0 : (double) totalRecoveryMs / recoveryCount; // Average recovery ms
        double avgLatency = completedRunCount == 0 ? 0.0 : (double) totalRunLatencyMs / completedRunCount; // Average run latency ms
        return new ReliabilityMetrics(runs.size(), completedRunCount, totalRetries, totalRollbacks, avgMttr, avgLatency); // Return snapshot
    }

    public Optional<WorkflowRun> getRun(String runId) { // Retrieves a run by ID for inspection or audit review
        return Optional.ofNullable(runs.get(runId)); // Return Optional run
    }
}
