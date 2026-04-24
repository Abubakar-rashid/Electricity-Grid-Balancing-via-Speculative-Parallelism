package com.gridmanagement.model;

/**
 * The result of evaluating a single routing candidate.
 *
 * <p>Instances are returned by worker threads (via {@code Future<RouteResult>})
 * and transmitted from worker nodes to the master as the payload of
 * {@code RESULT_RETURN} messages.
 *
 * <p>Wire format: {@code candidateId,costScore,evalTimeMs,workerId,feasible}
 */
public class RouteResult {

    /** Index of the evaluated candidate. */
    public final int candidateId;

    /**
     * Aggregate cost score (lower = better).
     * Computed as total energy loss + large penalty for constraint violations.
     */
    public final double costScore;

    /** Wall-clock time (ms) taken to evaluate this candidate. */
    public final long evalTimeMs;

    /** ID of the worker that produced this result. */
    public final int workerId;

    /** {@code true} if all physical constraints were satisfied. */
    public final boolean feasible;

    public RouteResult(int candidateId, double costScore,
                       long evalTimeMs, int workerId, boolean feasible) {
        this.candidateId = candidateId;
        this.costScore   = costScore;
        this.evalTimeMs  = evalTimeMs;
        this.workerId    = workerId;
        this.feasible    = feasible;
    }

    /** Sentinel "worst possible" result used for initialising comparisons. */
    public static final RouteResult WORST =
            new RouteResult(-1, Double.MAX_VALUE, 0, -1, false);

    // ── Serialisation ─────────────────────────────────────────────────────

    /** Serialises to a comma-separated string (no newlines). */
    public String serialize() {
        return candidateId + "," + costScore + "," + evalTimeMs + ","
                + workerId + "," + feasible;
    }

    /** Deserialises from the format produced by {@link #serialize()}. */
    public static RouteResult deserialize(String s) {
        String[] p = s.split(",");
        return new RouteResult(
                Integer.parseInt(p[0].trim()),
                Double.parseDouble(p[1].trim()),
                Long.parseLong(p[2].trim()),
                Integer.parseInt(p[3].trim()),
                Boolean.parseBoolean(p[4].trim())
        );
    }

    @Override
    public String toString() {
        return String.format(
                "RouteResult{id=%d, cost=%.2f, feasible=%b, worker=%d, time=%dms}",
                candidateId, costScore, feasible, workerId, evalTimeMs);
    }
}
