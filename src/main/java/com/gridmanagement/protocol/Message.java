package com.gridmanagement.protocol;

/**
 * Wire-protocol message for master ↔ worker communication.
 *
 * <h3>Wire format</h3>
 * {@code TYPE|payload\n}
 * <p>The newline acts as a framing delimiter (one message per line).
 * The pipe character separates the type token from the payload.
 * Payloads never contain newlines; they use commas and colons internally.
 *
 * <h3>Message flow</h3>
 * <pre>
 *  Worker → Master:  HANDSHAKE, TASK_REQUEST, RESULT_RETURN, STATUS_UPDATE
 *  Master → Worker:  GRID_INIT, TASK_ASSIGN, SHUTDOWN
 * </pre>
 *
 * <h3>Payload formats</h3>
 * <ul>
 *   <li>{@code HANDSHAKE}    –  {@code workerId,cores}</li>
 *   <li>{@code GRID_INIT}    –  {@code totalCandidates|serialisedGridSnapshot}
 *       <br>Note: the serialised grid itself may contain pipe characters
 *       (edge separator); callers must split on the <em>first</em> pipe only.</li>
 *   <li>{@code TASK_ASSIGN}  –  {@code chunkId,rangeStart,rangeEnd}</li>
 *   <li>{@code TASK_REQUEST} –  {@code workerId}</li>
 *   <li>{@code RESULT_RETURN}–  {@link com.gridmanagement.model.RouteResult#serialize()}</li>
 *   <li>{@code STATUS_UPDATE}–  {@code workerId,progressPct,cpuLoadPct}</li>
 *   <li>{@code SHUTDOWN}     –  (empty)</li>
 * </ul>
 */
public final class Message {

    // ── Type constants ────────────────────────────────────────────────────
    public static final String HANDSHAKE     = "HANDSHAKE";
    public static final String GRID_INIT     = "GRID_INIT";
    public static final String TASK_ASSIGN   = "TASK_ASSIGN";
    public static final String TASK_REQUEST  = "TASK_REQUEST";
    public static final String RESULT_RETURN = "RESULT_RETURN";
    public static final String STATUS_UPDATE = "STATUS_UPDATE";
    public static final String SHUTDOWN      = "SHUTDOWN";

    // ── Fields ────────────────────────────────────────────────────────────
    public final String type;
    public final String payload;

    public Message(String type, String payload) {
        this.type    = type;
        this.payload = payload == null ? "" : payload;
    }

    // ── (De)serialisation ─────────────────────────────────────────────────

    /** Returns the wire representation: {@code "TYPE|payload\n"}. */
    public String serialize() {
        return type + "|" + payload + "\n";
    }

    /**
     * Parses one line read from the socket.
     * The trailing newline should already have been stripped by
     * {@link java.io.BufferedReader#readLine()}.
     */
    public static Message deserialize(String line) {
        if (line == null || line.isBlank()) return new Message("UNKNOWN", "");
        int pipe = line.indexOf('|');
        if (pipe < 0) return new Message(line.trim(), "");
        return new Message(line.substring(0, pipe).trim(),
                           line.substring(pipe + 1));
    }

    // ── Factory helpers ───────────────────────────────────────────────────

    /** {@code HANDSHAKE} sent by worker immediately on connect. */
    public static Message handshake(int workerId, int cores) {
        return new Message(HANDSHAKE, workerId + "," + cores);
    }

    /**
     * {@code GRID_INIT} sent by master to each worker once.
     * @param totalCandidates total N across all workers
     * @param serialisedGrid  output of {@link com.gridmanagement.model.GridSnapshot#serialize()}
     */
    public static Message gridInit(int totalCandidates, String serialisedGrid) {
        return new Message(GRID_INIT, totalCandidates + "|" + serialisedGrid);
    }

    /** {@code TASK_ASSIGN} carrying a candidate index range [start, end). */
    public static Message taskAssign(int chunkId, int start, int end) {
        return new Message(TASK_ASSIGN, chunkId + "," + start + "," + end);
    }

    /** {@code TASK_REQUEST} sent by worker when it is ready for more work. */
    public static Message taskRequest(int workerId) {
        return new Message(TASK_REQUEST, String.valueOf(workerId));
    }

    /** {@code RESULT_RETURN} carrying the serialised local best result. */
    public static Message resultReturn(String serialisedResult) {
        return new Message(RESULT_RETURN, serialisedResult);
    }

    /**
     * {@code STATUS_UPDATE} sent periodically by each worker.
     * @param workerId    worker identifier
     * @param progressPct percentage of assigned candidates evaluated so far
     * @param cpuLoadPct  estimated CPU utilisation (0–100)
     */
    public static Message statusUpdate(int workerId, double progressPct, double cpuLoadPct) {
        return new Message(STATUS_UPDATE,
                           workerId + "," + progressPct + "," + cpuLoadPct);
    }

    /** {@code SHUTDOWN} sent by master after all results are collected. */
    public static Message shutdown() {
        return new Message(SHUTDOWN, "");
    }

    @Override
    public String toString() {
        String preview = payload.length() > 80
                       ? payload.substring(0, 80) + "…"
                       : payload;
        return "Message{" + type + ", payload=" + preview + "}";
    }
}
