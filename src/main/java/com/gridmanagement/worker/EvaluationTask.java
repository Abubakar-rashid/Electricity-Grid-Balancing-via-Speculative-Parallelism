package com.gridmanagement.worker;

import com.gridmanagement.grid.CandidateEvaluator;
import com.gridmanagement.grid.GridGenerator;
import com.gridmanagement.model.GridSnapshot;
import com.gridmanagement.model.RouteCandidate;
import com.gridmanagement.model.RouteResult;

import java.util.concurrent.Callable;

/**
 * A single unit of work submitted to the worker's {@link java.util.concurrent.ExecutorService}.
 *
 * <p>Implements {@link Callable}{@code <RouteResult>} so that the worker can
 * use {@code invokeAll()} to submit a batch and collect typed results via
 * {@code Future<RouteResult>} — consistent with the intra-node threading plan
 * in §3.4 of the specification.
 *
 * <p>Each task:
 * <ol>
 *   <li>Regenerates the routing candidate deterministically from its index
 *       using {@link GridGenerator#generateCandidate(GridSnapshot, int)}.</li>
 *   <li>Evaluates it with {@link CandidateEvaluator#evaluate(GridSnapshot,
 *       RouteCandidate, int)}.</li>
 *   <li>Returns the resulting {@link RouteResult} to the calling
 *       {@link WorkerNode} via the {@code Future}.</li>
 * </ol>
 *
 * <p>No shared mutable state — fully stateless and thread-safe.
 */
public class EvaluationTask implements Callable<RouteResult> {

    private final GridSnapshot grid;
    private final int          candidateId;
    private final int          workerId;

    /**
     * @param grid        read-only grid snapshot shared by all tasks on this worker
     * @param candidateId index of the candidate to generate and evaluate
     * @param workerId    ID of the owning worker (embedded in the result)
     */
    public EvaluationTask(GridSnapshot grid, int candidateId, int workerId) {
        this.grid        = grid;
        this.candidateId = candidateId;
        this.workerId    = workerId;
    }

    @Override
    public RouteResult call() {
        // Step 1: regenerate candidate deterministically
        RouteCandidate candidate = GridGenerator.generateCandidate(grid, candidateId);

        // Step 2: evaluate against physical constraints
        return CandidateEvaluator.evaluate(grid, candidate, workerId);
    }
}
