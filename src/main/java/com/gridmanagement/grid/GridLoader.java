package com.gridmanagement.grid;

import com.gridmanagement.model.GridSnapshot;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Loads a user-defined power grid from a JSON file.
 *
 * <h3>JSON format</h3>
 * <pre>
 * {
 *   "nodes": [
 *     { "id": 0, "name": "NodeA", "demand": 120 },
 *     ...
 *   ],
 *   "edges": [
 *     { "from": 0, "to": 1, "capacity": 500, "impedance": 12 },
 *     ...
 *   ],
 *   "generators": [
 *     { "node": 0, "output": 200, "capacity": 400 },
 *     ...
 *   ]
 * }
 * </pre>
 *
 * <p>All fields are integers. "name" on nodes is optional (ignored by the solver,
 * kept for human readability). Uses only the standard library — no external deps.
 */
public class GridLoader {

    // ── Public entry point ────────────────────────────────────────────────────

    /**
     * Parses {@code path} and returns a fully initialised {@link GridSnapshot}.
     *
     * @param path path to the JSON grid file
     * @return the loaded grid snapshot
     * @throws IOException          if the file cannot be read
     * @throws IllegalArgumentException if the JSON is malformed or invalid
     */
    public static GridSnapshot load(String path) throws IOException {
        String json = Files.readString(Path.of(path));
        return parse(json, path);
    }

    // ── Parser ────────────────────────────────────────────────────────────────

    private static GridSnapshot parse(String json, String path) {
        // ── nodes ──────────────────────────────────────────────────────────────
        List<int[]> nodeList = new ArrayList<>();   // [id, demand]
        String nodesBlock = extractArray(json, "nodes");
        for (String obj : splitObjects(nodesBlock)) {
            int id     = intField(obj, "id");
            int demand = intField(obj, "demand");
            nodeList.add(new int[]{id, demand});
        }
        if (nodeList.isEmpty()) {
            throw new IllegalArgumentException(path + ": 'nodes' array is empty or missing.");
        }

        // Sort by id and validate contiguous 0-based ids
        nodeList.sort(Comparator.comparingInt(a -> a[0]));
        for (int i = 0; i < nodeList.size(); i++) {
            if (nodeList.get(i)[0] != i) {
                throw new IllegalArgumentException(
                        path + ": node ids must be 0-based and contiguous. Missing id=" + i);
            }
        }

        int nodeCount = nodeList.size();
        int[] demand  = new int[nodeCount];
        for (int[] n : nodeList) demand[n[0]] = n[1];

        // ── edges ──────────────────────────────────────────────────────────────
        List<int[]> edgeList = new ArrayList<>();   // [from, to, capacity, impedance]
        String edgesBlock = extractArray(json, "edges");
        for (String obj : splitObjects(edgesBlock)) {
            int from      = intField(obj, "from");
            int to        = intField(obj, "to");
            int capacity  = intField(obj, "capacity");
            int impedance = intField(obj, "impedance");

            if (from < 0 || from >= nodeCount)
                throw new IllegalArgumentException(path + ": edge 'from'=" + from + " is out of range [0," + nodeCount + ")");
            if (to < 0 || to >= nodeCount)
                throw new IllegalArgumentException(path + ": edge 'to'=" + to + " is out of range [0," + nodeCount + ")");
            if (capacity <= 0)
                throw new IllegalArgumentException(path + ": edge capacity must be > 0, got " + capacity);
            if (impedance <= 0)
                throw new IllegalArgumentException(path + ": edge impedance must be > 0, got " + impedance);

            edgeList.add(new int[]{from, to, capacity, impedance});
        }
        if (edgeList.isEmpty()) {
            throw new IllegalArgumentException(path + ": 'edges' array is empty or missing.");
        }

        int edgeCount = edgeList.size();
        int[][] edges = edgeList.toArray(new int[0][]);

        // ── generators ────────────────────────────────────────────────────────
        List<int[]> genList = new ArrayList<>();   // [node, output, capacity]
        String gensBlock = extractArray(json, "generators");
        for (String obj : splitObjects(gensBlock)) {
            int node     = intField(obj, "node");
            int output   = intField(obj, "output");
            int capacity = intField(obj, "capacity");

            if (node < 0 || node >= nodeCount)
                throw new IllegalArgumentException(path + ": generator node=" + node + " out of range");
            if (output < 0)
                throw new IllegalArgumentException(path + ": generator output must be >= 0");
            if (capacity <= 0)
                throw new IllegalArgumentException(path + ": generator capacity must be > 0");

            genList.add(new int[]{node, output, capacity});
        }
        if (genList.isEmpty()) {
            throw new IllegalArgumentException(path + ": 'generators' array is empty or missing.");
        }

        int[] genNodes    = genList.stream().mapToInt(g -> g[0]).toArray();
        int[] genOutput   = genList.stream().mapToInt(g -> g[1]).toArray();
        int[] genCapacity = genList.stream().mapToInt(g -> g[2]).toArray();

        System.out.printf("[GridLoader] Loaded from %s: %d nodes, %d edges, %d generators%n",
                path, nodeCount, edgeCount, genList.size());

        return new GridSnapshot(nodeCount, edgeCount, edges,
                genOutput, genCapacity, genNodes, demand);
    }

    // ── Minimal JSON helpers (no external libraries) ──────────────────────────

    /** Extracts the content of the first JSON array with the given key. */
    private static String extractArray(String json, String key) {
        String searchKey = "\"" + key + "\"";
        int keyIdx = json.indexOf(searchKey);
        if (keyIdx < 0) return "";
        int bracketOpen = json.indexOf('[', keyIdx + searchKey.length());
        if (bracketOpen < 0) return "";
        int depth = 0;
        int i = bracketOpen;
        while (i < json.length()) {
            char c = json.charAt(i);
            if      (c == '[') depth++;
            else if (c == ']') { depth--; if (depth == 0) return json.substring(bracketOpen + 1, i); }
            i++;
        }
        throw new IllegalArgumentException("Unterminated array for key: " + key);
    }

    /** Splits a JSON array body (without the outer [ ]) into individual { } objects. */
    private static List<String> splitObjects(String arrayBody) {
        List<String> result = new ArrayList<>();
        int depth = 0, start = -1;
        for (int i = 0; i < arrayBody.length(); i++) {
            char c = arrayBody.charAt(i);
            if (c == '{') {
                if (depth == 0) start = i;
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && start >= 0) {
                    result.add(arrayBody.substring(start, i + 1));
                    start = -1;
                }
            }
        }
        return result;
    }

    /** Parses the integer value of a JSON field by name. */
    private static int intField(String obj, String field) {
        String key = "\"" + field + "\"";
        int ki = obj.indexOf(key);
        if (ki < 0) throw new IllegalArgumentException("Missing field '" + field + "' in: " + obj);
        int colon = obj.indexOf(':', ki + key.length());
        if (colon < 0) throw new IllegalArgumentException("Malformed field '" + field + "' in: " + obj);
        // scan digits (and optional minus) after the colon
        int j = colon + 1;
        while (j < obj.length() && (obj.charAt(j) == ' ' || obj.charAt(j) == '\t'
                || obj.charAt(j) == '\r' || obj.charAt(j) == '\n')) j++;
        int numStart = j;
        if (j < obj.length() && obj.charAt(j) == '-') j++;
        while (j < obj.length() && Character.isDigit(obj.charAt(j))) j++;
        if (j == numStart) throw new IllegalArgumentException("Non-integer value for '" + field + "' in: " + obj);
        return Integer.parseInt(obj.substring(numStart, j).trim());
    }
}
