package com.gridmanagement.grid;

import com.gridmanagement.model.GridSnapshot;
import com.gridmanagement.model.RouteCandidate;
import com.gridmanagement.model.RouteResult;

/**
 * Stateless evaluator for a single routing candidate.
 *
 * <h3>Algorithm</h3>
 * <ol>
 *   <li><b>Capacity check (O(E)):</b> for every directed edge, verify that the
 *       assigned flow does not exceed the line's rated capacity and is
 *       non-negative.  Each violation accumulates a large cost penalty.</li>
 *   <li><b>Power-balance check (O(V + E)):</b> compute the net power injection
 *       at every node (generation − demand) and the net flow entering/leaving
 *       each node from the edge assignments.  Any node imbalance beyond a 10 MW
 *       tolerance is penalised.</li>
 *   <li><b>Generator capacity check (O(G)):</b> verify that no generator
 *       operates above its rated maximum.</li>
 *   <li><b>Cost score:</b> total energy loss = Σ(flow_i × impedance_i)  plus
 *       the accumulated penalty.  Lower score = better routing.</li>
 * </ol>
 *
 * <p>All methods are pure functions: no shared mutable state, fully thread-safe.
 */
public class CandidateEvaluator {

    /**
     * Cost penalty multiplier added per MW of constraint violation.
     * Large enough to dominate the energy-loss term so infeasible candidates
     * are always ranked below feasible ones.
     */
    private static final double VIOLATION_PENALTY = 1_000_000.0;

    /** Acceptable power imbalance at a node (MW). */
    private static final double BALANCE_TOLERANCE = 10.0;

    // Construction forbidden — pure utility class
    private CandidateEvaluator() {}

    /**
     * Evaluates {@code candidate} on {@code grid}.
     *
     * @param grid      read-only grid snapshot
     * @param candidate the routing configuration to evaluate
     * @param workerId  identifier of the calling worker (stored in result)
     * @return a {@link RouteResult} containing cost score, feasibility flag,
     *         and wall-clock evaluation time
     */
    public static RouteResult evaluate(GridSnapshot grid,
                                       RouteCandidate candidate,
                                       int workerId) {
        long t0 = System.currentTimeMillis();

        double totalCost  = 0.0;
        double penalty    = 0.0;
        boolean feasible  = true;

        // ── Phase 1: Edge capacity constraints (O(E)) ────────────────────
        for (int i = 0; i < grid.edgeCount; i++) {
            double flow     = candidate.flowPerEdge[i];
            double capacity = grid.edges[i][2];
            double impedance = grid.edges[i][3];

            // Energy-loss contribution (I²R analogy: flow × impedance)
            totalCost += flow * impedance;

            if (flow < 0) {
                penalty  += VIOLATION_PENALTY * (-flow);
                feasible  = false;
            } else if (flow > capacity) {
                penalty  += VIOLATION_PENALTY * (flow - capacity);
                feasible  = false;
            }
        }

        // ── Phase 2: Nodal power balance (O(V + E)) ──────────────────────
        //    netInjection[v] = generation_v − demand_v  (positive → source)
        double[] netInjection   = new double[grid.nodeCount];
        double[] nodalFlowDelta = new double[grid.nodeCount];

        // Inject generation at generator nodes
        for (int g = 0; g < grid.generatorNodes.length; g++) {
            int node = grid.generatorNodes[g];
            if (node >= 0 && node < grid.nodeCount) {
                netInjection[node] += grid.generatorOutput[g];
            }
        }

        // Subtract demand at every node
        for (int v = 0; v < grid.nodeCount; v++) {
            netInjection[v] -= grid.demand[v];
        }

        // Accumulate flow entering/leaving each node
        for (int i = 0; i < grid.edgeCount; i++) {
            double flow = candidate.flowPerEdge[i];
            nodalFlowDelta[grid.edges[i][0]] -= flow;  // leaving
            nodalFlowDelta[grid.edges[i][1]] += flow;  // arriving
        }

        // Check balance at each node
        for (int v = 0; v < grid.nodeCount; v++) {
            double imbalance = Math.abs(nodalFlowDelta[v] - netInjection[v]);
            if (imbalance > BALANCE_TOLERANCE) {
                penalty  += imbalance * 100.0;
                feasible  = false;
            }
        }

        // ── Phase 3: Generator capacity constraints (O(G)) ────────────────
        for (int g = 0; g < grid.generatorOutput.length; g++) {
            if (grid.generatorOutput[g] > grid.generatorCapacity[g]) {
                penalty  += VIOLATION_PENALTY;
                feasible  = false;
            }
        }

        double finalCost  = totalCost + penalty;
        long   evalTimeMs = System.currentTimeMillis() - t0;

        return new RouteResult(candidate.candidateId, finalCost,
                               evalTimeMs, workerId, feasible);
    }
}
