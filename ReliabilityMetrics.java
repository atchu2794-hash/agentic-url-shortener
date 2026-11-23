package com.example.agentic_url_shortener.Entity;

public record ReliabilityMetrics( // Tracks workflow health and recovery metrics
                           int totalRuns,         // Total orchestration runs started
                           int successfulRuns,    // Runs that reached RELEASE_READINESS completed
                           int totalRetries,      // Count of bounded retries triggered
                           int totalRollbacks,    // Count of rollbacks to last safe snapshot
                           double averageMttrMs,  // Mean time to recover from failures in milliseconds
                           double averageLatencyMs// Mean end-to-end run latency in milliseconds
) {}
