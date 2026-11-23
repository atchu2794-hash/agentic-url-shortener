package com.example.agentic_url_shortener.Entity;

public enum StageStatus { // Lifecycle states for each stage in the graph
    PENDING,                 // Not yet eligible to run
    WAITING_HUMAN_APPROVAL,  // Paused at a human-in-the-loop governance checkpoint
    RUNNING,                 // Currently executing
    COMPLETED,               // Passed execution and exit gate
    FAILED,                  // Failed after retries or blocked by policy
    INVALIDATED              // Upstream changed; needs re-execution (dynamic re-planning)
}
