package com.example.agentic_url_shortener.Entity;

import java.time.Instant;

public record DecisionRecord( // Audit trail entry explaining why an action/choice was made
                              StageId stage,         // Stage where decision occurred
                              String decision,       // What was decided (e.g., "302 over 301 for analytics")
                              String rationale,      // Why it was chosen
                              Instant timestamp      // When the decision was recorded
) {}
