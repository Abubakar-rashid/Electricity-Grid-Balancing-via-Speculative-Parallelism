package com.gridmanagement.model;

import java.io.Serializable;

/**
 * Immutable snapshot of the power grid at a single point in time.
 *
 * <p>Holds topology (directed weighted graph), generator states, and
 * per-node demand values.  The snapshot is shared read-only by all
 * evaluation threads and is serialised once for network transport.
 *
 * <p>Wire format (newline-safe, no external libraries required):
 * <pre>
 *  nodeCount,edgeCount;from,to,cap,imp|...|;genOut,..;genCap,..;genNodes,..;demand,..
 * </pre>
 */
public class GridSnapshot implements Serializable {

    private static final long serialVersionUID = 1L;

    // ── Topology ─────────────────────────────────────────────────────────
    public final int nodeCount;
    public final int edgeCount;

    /**
     * edges[i] = { from, to, capacity (MW), impedance (mΩ) }
     */
    public final int[][] edges;

    /**
     * Adjacency list: adjList[node] = array of edge-indices leaving that node.
     * Built once at construction from {@code edges}; never mutated.
     */
    public final int[][] adjList;

    // ── Generator data ────────────────────────────────────────────────────
    /** Current real-power output (MW) of each generator. */
    public final int[] generatorOutput;
    /** Rated maximum capacity (MW) of each generator. */
    public final int[] generatorCapacity;
    /** Node index at which each generator is connected. */
    public final int[] generatorNodes;

    // ── Load data ─────────────────────────────────────────────────────────
    /** Current power demand (MW) at each node. */
    public final int[] demand;

    // ─────────────────────────────────────────────────────────────────────

    public GridSnapshot(int nodeCount, int edgeCount, int[][] edges,
                        int[] generatorOutput, int[] generatorCapacity,
                        int[] generatorNodes, int[] demand) {
        this.nodeCount        = nodeCount;
        this.edgeCount        = edgeCount;
        this.edges            = edges;
        this.generatorOutput  = generatorOutput;
        this.generatorCapacity = generatorCapacity;
        this.generatorNodes   = generatorNodes;
        this.demand           = demand;
        this.adjList          = buildAdjacencyList(nodeCount, edges);
    }

    // ── Adjacency list builder ────────────────────────────────────────────

    private static int[][] buildAdjacencyList(int nodeCount, int[][] edges) {
        int[] degree = new int[nodeCount];
        for (int[] e : edges) degree[e[0]]++;

        int[][] adj = new int[nodeCount][];
        for (int i = 0; i < nodeCount; i++) adj[i] = new int[degree[i]];

        int[] ptr = new int[nodeCount];
        for (int i = 0; i < edges.length; i++) {
            int from = edges[i][0];
            adj[from][ptr[from]++] = i;
        }
        return adj;
    }

    // ── Serialisation ─────────────────────────────────────────────────────

    /**
     * Serialises the snapshot to a compact, newline-free string.
     * The string is safe to embed as the payload of a protocol message.
     */
    public String serialize() {
        StringBuilder sb = new StringBuilder(edgeCount * 20 + nodeCount * 10);

        // Section 0: dimensions
        sb.append(nodeCount).append(',').append(edgeCount).append(';');

        // Section 1: edges  (pipe-delimited, each edge = from,to,cap,imp)
        for (int[] e : edges) {
            sb.append(e[0]).append(',')
              .append(e[1]).append(',')
              .append(e[2]).append(',')
              .append(e[3]).append('|');
        }
        sb.append(';');

        // Sections 2-5: generator output, capacity, nodes, demand
        appendIntArray(sb, generatorOutput);  sb.append(';');
        appendIntArray(sb, generatorCapacity); sb.append(';');
        appendIntArray(sb, generatorNodes);    sb.append(';');
        appendIntArray(sb, demand);

        return sb.toString();
    }

    /** Deserialises a string produced by {@link #serialize()}. */
    public static GridSnapshot deserialize(String s) {
        String[] sections = s.split(";", -1);   // -1 to keep trailing empty strings

        // Section 0
        String[] dims = sections[0].split(",");
        int nodeCount = Integer.parseInt(dims[0].trim());
        int edgeCount = Integer.parseInt(dims[1].trim());

        // Section 1 – edges
        int[][] edges = new int[edgeCount][4];
        if (edgeCount > 0 && sections.length > 1 && !sections[1].isEmpty()) {
            String[] tokens = sections[1].split("\\|");
            for (int i = 0; i < edgeCount && i < tokens.length; i++) {
                if (tokens[i].isEmpty()) continue;
                String[] v = tokens[i].split(",");
                edges[i][0] = Integer.parseInt(v[0].trim());
                edges[i][1] = Integer.parseInt(v[1].trim());
                edges[i][2] = Integer.parseInt(v[2].trim());
                edges[i][3] = Integer.parseInt(v[3].trim());
            }
        }

        // Sections 2-5
        int[] genOut  = parseIntArray(sections.length > 2 ? sections[2] : "");
        int[] genCap  = parseIntArray(sections.length > 3 ? sections[3] : "");
        int[] genNodes = parseIntArray(sections.length > 4 ? sections[4] : "");
        int[] demand  = parseIntArray(sections.length > 5 ? sections[5] : "");

        return new GridSnapshot(nodeCount, edgeCount, edges, genOut, genCap, genNodes, demand);
    }

    // ── Private helpers ───────────────────────────────────────────────────

    private static void appendIntArray(StringBuilder sb, int[] arr) {
        for (int i = 0; i < arr.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(arr[i]);
        }
    }

    private static int[] parseIntArray(String s) {
        if (s == null || s.isBlank()) return new int[0];
        String[] parts = s.split(",");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) out[i] = Integer.parseInt(parts[i].trim());
        return out;
    }
}
