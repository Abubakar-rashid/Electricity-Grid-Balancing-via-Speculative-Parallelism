package com.gridmanagement.master;

import com.gridmanagement.model.RouteResult;
import com.gridmanagement.protocol.Message;

import java.io.*;
import java.net.Socket;

/**
 * Per-worker handler running on the master side.
 *
 * <p>One {@code WorkerProxy} instance is created for every worker that
 * connects to the master's server socket.  It runs on a dedicated daemon
 * thread, reads incoming messages from the socket, and dispatches them to the
 * appropriate {@link MasterNode} callback.
 *
 * <h3>Concurrency notes</h3>
 * <ul>
 *   <li>Reading happens only on this proxy's dedicated thread — no lock needed
 *       for reads.</li>
 *   <li>{@link #send(Message)} is {@code synchronized} because the master's
 *       main thread (broadcast of {@code GRID_INIT}/{@code SHUTDOWN}) and this
 *       proxy thread may both call it concurrently.</li>
 * </ul>
 */
public class WorkerProxy implements Runnable {

    private final Socket       socket;
    private final MasterNode   master;
    private final BufferedReader reader;
    private final PrintWriter    writer;

    /** Set when the first HANDSHAKE from this worker is received. */
    private volatile int workerId = -1;
    private int currentChunkSize = 0;

    public WorkerProxy(Socket socket, MasterNode master) throws IOException {
        this.socket = socket;
        this.master = master;
        this.reader = new BufferedReader(
                new InputStreamReader(socket.getInputStream()));
        this.writer = new PrintWriter(
                new OutputStreamWriter(socket.getOutputStream()), true);
    }

    // ── Runnable ──────────────────────────────────────────────────────────

    @Override
    public void run() {
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                dispatch(Message.deserialize(line));
            }
        } catch (IOException e) {
            String msg = e.getMessage();
            if (msg != null && !msg.contains("closed") && !msg.contains("reset")) {
                System.err.printf("[WorkerProxy-%d] I/O error: %s%n", workerId, msg);
            }
        }
    }

    // ── Message dispatch ──────────────────────────────────────────────────

    private void dispatch(Message msg) {
        switch (msg.type) {

            case Message.HANDSHAKE -> {
                String[] p = msg.payload.split(",");
                workerId = Integer.parseInt(p[0].trim());
                int cores = Integer.parseInt(p[1].trim());
                System.out.printf("[MASTER] Worker-%d registered  (%d cores)%n",
                        workerId, cores);
            }

            case Message.TASK_REQUEST -> {
                // Worker explicitly requests its first chunk
                currentChunkSize = master.dispatchNextChunk(this);
            }

            case Message.RESULT_RETURN -> {
                // Store result, then immediately push next chunk (dynamic assignment)
                RouteResult result = RouteResult.deserialize(msg.payload);
                master.acceptResult(result, currentChunkSize);
                currentChunkSize = master.dispatchNextChunk(this);   // sends TASK_ASSIGN or nothing
            }

            case Message.STATUS_UPDATE -> {
                String[] p = msg.payload.split(",");
                master.handleStatus(
                        Integer.parseInt(p[0].trim()),
                        Double.parseDouble(p[1].trim()),
                        Double.parseDouble(p[2].trim()));
            }

            default -> System.err.printf(
                    "[WorkerProxy-%d] Unknown message type: %s%n",
                    workerId, msg.type);
        }
    }

    // ── Thread-safe send ──────────────────────────────────────────────────

    /**
     * Sends a message to the connected worker.
     * Synchronised so the master's main thread and this proxy thread cannot
     * interleave writes on the same socket output stream.
     */
    public synchronized void send(Message msg) {
        writer.print(msg.serialize());
        writer.flush();
    }

    public int getWorkerId() { return workerId; }
}
