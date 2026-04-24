package com.gridmanagement.grid;

import com.gridmanagement.model.GridSnapshot;
import com.gridmanagement.model.RouteCandidate;

import java.util.Random;

/**
 * Synthetic grid factory and deterministic candidate generator.
 *
 * <p>All methods are stateless and thread-safe.  The same {@code GRID_SEED}
 * always produces the same grid; the same {@code CANDIDATE_BASE_SEED + id}
 * always produces the same candidate — so workers reconstruct candidates
 * locally without receiving them over the network.
 */
public class GridGenerator {

    /** Fixed seed for reproducible grid topology. */
    private static final long GRID_SEED = 42L;

    /**
     * Base seed for candidate generation.
     * Candidate {@code k} uses seed {@code CANDIDATE_BASE_SEED + k}.
     */
    private static final long CANDIDATE_BASE_SEED = 1_000_003L;

    // ── Grid construction ─────────────────────────────────────────────────

    /**
     * Builds a synthetic power grid with {@code nodeCount} nodes and
     * {@code edgeCount} directed, weighted edges.
     *
     * <p>Connectivity guarantee: the first {@code (nodeCount-1)} edges form a
     * random spanning tree so that the graph is always connected.
     *
     * @param nodeCount number of nodes (generators + substations + load centres)
     * @param edgeCount number of directed transmission lines
     * @return a fully initialised, immutable {@link GridSnapshot}
     */
    public static GridSnapshot generateGrid(int nodeCount, int edgeCount) {
        Random rng = new Random(GRID_SEED);

        // ── Edges ────────────────────────────────────────────────────────
        int[][] edges = new int[edgeCount][4];

        // Spanning tree: guarantees connectivity (nodeCount-1 edges)
        int[] perm = shuffledPermutation(nodeCount, rng);
        int treeSize = Math.min(nodeCount - 1, edgeCount);
        for (int i = 0; i < treeSize; i++) {
            edges[i][0] = perm[i];
            edges[i][1] = perm[i + 1];
            edges[i][2] = 100 + rng.nextInt(900);   // capacity:  100–999 MW
            edges[i][3] = 1   + rng.nextInt(50);    // impedance: 1–50 mΩ
        }

        // Remaining edges: random inter-node links
        for (int i = treeSize; i < edgeCount; i++) {
            int from = rng.nextInt(nodeCount);
            int to;
            do { to = rng.nextInt(nodeCount); } while (to == from);
            edges[i][0] = from;
            edges[i][1] = to;
            edges[i][2] = 100 + rng.nextInt(900);
            edges[i][3] = 1   + rng.nextInt(50);
        }

        // ── Generators ───────────────────────────────────────────────────
        // Approximately 1 generator per 3 nodes, evenly spread across the graph
        int numGenerators = Math.max(1, nodeCount / 3);
        int[] genNodes    = new int[numGenerators];
        int[] genOutput   = new int[numGenerators];
        int[] genCapacity = new int[numGenerators];

        for (int i = 0; i < numGenerators; i++) {
            genNodes[i]    = (i * 3) % nodeCount;
            genCapacity[i] = 500 + rng.nextInt(1500);           // 500–2000 MW
            genOutput[i]   = genCapacity[i] / 2
                           + rng.nextInt(Math.max(1, genCapacity[i] / 2)); // 50–100 %
        }

        // ── Demand ───────────────────────────────────────────────────────
        int[] demand = new int[nodeCount];
        for (int i = 0; i < nodeCount; i++) {
            demand[i] = rng.nextInt(150);  // 0–149 MW per node
        }

        return new GridSnapshot(nodeCount, edgeCount, edges,
                                genOutput, genCapacity, genNodes, demand);
    }

    // ── Candidate generation ──────────────────────────────────────────────

    /**
     * Generates a single routing candidate deterministically.
     *
     * <p>Each edge receives a random flow in [0, capacity] drawn from a seeded
     * PRNG specific to this candidate.  Because the seed is a function of
     * {@code candidateId} only, workers can generate any candidate independently
     * without coordination.
     *
     * <p>Time complexity: O(E).
     *
     * @param grid        the shared grid snapshot
     * @param candidateId unique index of the candidate to generate
     * @return a {@link RouteCandidate} with per-edge flow assignments
     */
    public static RouteCandidate generateCandidate(GridSnapshot grid, int candidateId) {
        Random rng = new Random(CANDIDATE_BASE_SEED + candidateId);
        double[] flow = new double[grid.edgeCount];
        for (int i = 0; i < grid.edgeCount; i++) {
            double cap = grid.edges[i][2];
            // Allow a small fraction of candidates to violate capacity
            // so the feasibility constraint is meaningful
            flow[i] = rng.nextDouble() * cap * (candidateId % 7 == 0 ? 1.15 : 1.0);
        }
        return new RouteCandidate(candidateId, flow);
    }

    // ── Utility ───────────────────────────────────────────────────────────

    /** Returns a Fisher-Yates shuffle of [0, n). */
    private static int[] shuffledPermutation(int n, Random rng) {
        int[] arr = new int[n];
        for (int i = 0; i < n; i++) arr[i] = i;
        for (int i = n - 1; i > 0; i--) {
            int j = rng.nextInt(i + 1);
            int tmp = arr[i]; arr[i] = arr[j]; arr[j] = tmp;
        }
        return arr;
    }
}
