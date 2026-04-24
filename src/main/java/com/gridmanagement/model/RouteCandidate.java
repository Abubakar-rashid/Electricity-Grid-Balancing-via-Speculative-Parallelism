package com.gridmanagement.model;

/**
 * A single routing candidate configuration.
 *
 * <p>Specifies the real-power flow (MW) assigned to every directed edge
 * in the grid for this candidate.  Candidates are generated deterministically
 * by {@link com.gridmanagement.grid.GridGenerator#generateCandidate} so that
 * any worker can recreate candidate {@code k} from just the candidateId and
 * the shared grid snapshot — no network transfer of candidate data needed.
 */
public class RouteCandidate {

    /** Unique index of this candidate in the global candidate set [0, N). */
    public final int candidateId;

    /**
     * Power flow (MW) assigned to each edge.
     * {@code flowPerEdge[i]} corresponds to {@code GridSnapshot.edges[i]}.
     */
    public final double[] flowPerEdge;

    public RouteCandidate(int candidateId, double[] flowPerEdge) {
        this.candidateId = candidateId;
        this.flowPerEdge = flowPerEdge;
    }
}
