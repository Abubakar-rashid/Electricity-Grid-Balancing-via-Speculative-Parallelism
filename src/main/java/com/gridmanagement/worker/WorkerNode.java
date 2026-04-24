package com.gridmanagement.worker;

import com.gridmanagement.grid.GridGenerator;
import com.gridmanagement.model.GridSnapshot;
import com.gridmanagement.model.RouteResult;
import com.gridmanagement.protocol.Message;

import java.io.*;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Worker node — evaluates routing candidates using an intra-node thread pool.
 *
 * <h3>Lifecycle</h3>
 * <ol>
 *   <li>Connect to master and send {@code HANDSHAKE}.</li>
 *   <li>Create a fixed-size {@link ExecutorService} with one thread per logical
 *       CPU core (via {@code Runtime.availableProcessors()}).</li>
 *   <li>Receive {@code GRID_INIT}: deserialise the {@link GridSnapshot}.</li>
 *   <li>Send {@code TASK_REQUEST} to obtain the first chunk.</li>
 *   <li>On {@code TASK_ASSIGN}: submit one {@link EvaluationTask} per candidate
 *       index in [start, end) to the thread pool; call
 *       {@code invokeAll()} to block until all tasks complete; find the local
 *       best via an {@link AtomicReference} updated with CAS; send
 *       {@code RESULT_RETURN}.</li>
 *   <li>Master immediately sends the next {@code TASK_ASSIGN} (or nothing if
 *       the queue is exhausted).  Worker blocks on {@code readLine()} waiting
 *       for either the next chunk or {@code SHUTDOWN}.</li>
 *   <li>On {@code SHUTDOWN}: exit the message loop, shut down the thread pool.</li>
 * </ol>
 *
 * <h3>Concurrency notes</h3>
 * <ul>
 *   <li>{@link #send(Message)} is {@code synchronized}: the status-update
 *       daemon thread and the evaluation thread both call it.</li>
 *   <li>The {@link AtomicReference} holding the local best is updated safely
 *       from multiple {@code invokeAll} futures via an accumulate-and-get
 *       approach.</li>
 * </ul>
 */
public class WorkerNode implements Runnable {

    // ── Config ────────────────────────────────────────────────────────────
    private final int    workerId;
    private final String masterHost;
    private final int    masterPort;
    private final int    threadCount;

    // ── Runtime state ─────────────────────────────────────────────────────
    private GridSnapshot    grid;
    private ExecutorService threadPool;
    private PrintWriter     writer;
    private BufferedReader  reader;

    // ── Progress tracking (for STATUS_UPDATE) ─────────────────────────────
    private final AtomicInteger candidatesEvaluated    = new AtomicInteger(0);
    private final AtomicInteger candidatesAssignedTotal = new AtomicInteger(0);

    // ── Per-worker statistics ─────────────────────────────────────────────
    private int    chunksProcessed = 0;
    private double localBestCost   = Double.MAX_VALUE;
    private int    localBestId     = -1;

    // ─────────────────────────────────────────────────────────────────────

    public WorkerNode(int workerId, String masterHost, int masterPort) {
        this.workerId   = workerId;
        this.masterHost = masterHost;
        this.masterPort = masterPort;
        this.threadCount = Runtime.getRuntime().availableProcessors();
    }

    // ── Runnable ──────────────────────────────────────────────────────────

    @Override
    public void run() {
        workerBanner();
        System.out.printf("[Worker-%d] Using %d threads  connecting to %s:%d%n",
                workerId, threadCount, masterHost, masterPort);

        try (Socket socket = new Socket(masterHost, masterPort)) {
            reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream()));
            writer = new PrintWriter(
                    new OutputStreamWriter(socket.getOutputStream()), true);

            // ── Handshake ─────────────────────────────────────────────────
            send(Message.handshake(workerId, threadCount));

            // ── Thread pool ───────────────────────────────────────────────
            threadPool = Executors.newFixedThreadPool(threadCount,
                    r -> {
                        Thread t = new Thread(r,
                                "EvalThread-w" + workerId + "-" + Thread.activeCount());
                        t.setDaemon(true);
                        return t;
                    });

            // ── Status update daemon ──────────────────────────────────────
            startStatusDaemon();

            // ── Main message loop ─────────────────────────────────────────
            String line;
            while ((line = reader.readLine()) != null) {
                Message msg = Message.deserialize(line);
                boolean cont = dispatch(msg);
                if (!cont) break;
            }

        } catch (IOException e) {
            System.err.printf("[Worker-%d] Connection error: %s%n",
                    workerId, e.getMessage());
        } finally {
            shutdownPool();
        }

        System.out.printf("[Worker-%d] Finished.  Chunks processed: %d  " +
                          "Local best: candidate #%d  cost=%.2f%n",
                workerId, chunksProcessed, localBestId, localBestCost);
    }

    // ── Message dispatch ──────────────────────────────────────────────────

    /** @return {@code false} when the worker should stop (SHUTDOWN received). */
    private boolean dispatch(Message msg) {
        return switch (msg.type) {
            case Message.GRID_INIT -> {
                onGridInit(msg.payload);
                // Request first chunk
                send(Message.taskRequest(workerId));
                yield true;
            }
            case Message.TASK_ASSIGN -> {
                onTaskAssign(msg.payload);
                yield true;
            }
            case Message.SHUTDOWN -> {
                System.out.printf("[Worker-%d] SHUTDOWN received.%n", workerId);
                yield false;
            }
            default -> {
                System.err.printf("[Worker-%d] Unknown message: %s%n",
                        workerId, msg.type);
                yield true;
            }
        };
    }

    // ── GRID_INIT handler ─────────────────────────────────────────────────

    private void onGridInit(String payload) {
        // payload: "totalCandidates|serialisedGrid"
        int sep = payload.indexOf('|');
        int totalCandidates = Integer.parseInt(payload.substring(0, sep).trim());
        String serialisedGrid = payload.substring(sep + 1);

        grid = GridSnapshot.deserialize(serialisedGrid);
        System.out.printf("[Worker-%d] GRID_INIT received: %d nodes  %d edges  " +
                          "total %d candidates%n",
                workerId, grid.nodeCount, grid.edgeCount, totalCandidates);
    }

    // ── TASK_ASSIGN handler ───────────────────────────────────────────────

    private void onTaskAssign(String payload) {
        // payload: "chunkId,start,end"
        String[] parts = payload.split(",");
        int chunkId = Integer.parseInt(parts[0].trim());
        int start   = Integer.parseInt(parts[1].trim());
        int end     = Integer.parseInt(parts[2].trim());
        int count   = end - start;

        candidatesAssignedTotal.addAndGet(count);
        System.out.printf("[Worker-%d] Chunk #%d  [%d, %d)  %d candidates%n",
                workerId, chunkId, start, end, count);

        long t0 = System.currentTimeMillis();

        // ── Build task list ───────────────────────────────────────────────
        List<Callable<RouteResult>> tasks = new ArrayList<>(count);
        for (int id = start; id < end; id++) {
            tasks.add(new EvaluationTask(grid, id, workerId));
        }

        // ── Submit to thread pool and wait ────────────────────────────────
        AtomicReference<RouteResult> chunkBest =
                new AtomicReference<>(RouteResult.WORST);

        try {
            List<Future<RouteResult>> futures = threadPool.invokeAll(tasks);

            for (Future<RouteResult> future : futures) {
                try {
                    RouteResult r = future.get();
                    candidatesEvaluated.incrementAndGet();

                    // CAS-based minimum update (lock-free)
                    chunkBest.accumulateAndGet(r,
                            (a, b) -> a.costScore <= b.costScore ? a : b);

                } catch (ExecutionException ex) {
                    System.err.printf("[Worker-%d] Task error: %s%n",
                            workerId, ex.getCause().getMessage());
                }
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            System.err.printf("[Worker-%d] Interrupted during invokeAll%n", workerId);
        }

        RouteResult best = chunkBest.get();
        long elapsed = System.currentTimeMillis() - t0;

        // Update running local stats
        if (best.costScore < localBestCost) {
            localBestCost = best.costScore;
            localBestId   = best.candidateId;
        }
        chunksProcessed++;

        System.out.printf("[Worker-%d] Chunk #%d done in %d ms  " +
                          "local best: candidate #%d  cost=%.2f%n",
                workerId, chunkId, elapsed, best.candidateId, best.costScore);

        // ── Return result to master ───────────────────────────────────────
        send(Message.resultReturn(best.serialize()));
        // Master will immediately push next TASK_ASSIGN or send SHUTDOWN
    }

    // ── Status update daemon ──────────────────────────────────────────────

    private void startStatusDaemon() {
        Thread t = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(2_000);
                    int   total    = candidatesAssignedTotal.get();
                    int   done     = candidatesEvaluated.get();
                    double pct     = total > 0 ? 100.0 * done / total : 0.0;
                    double cpuLoad = estimateCpuLoad();
                    send(Message.statusUpdate(workerId, pct, cpuLoad));
                } catch (InterruptedException e) {
                    break;
                }
            }
        }, "StatusDaemon-" + workerId);
        t.setDaemon(true);
        t.start();
    }

    private double estimateCpuLoad() {
        try {
            // com.sun.management.OperatingSystemMXBean (Java 17)
            Object osBean = java.lang.management.ManagementFactory
                    .getOperatingSystemMXBean();
            java.lang.reflect.Method m =
                    osBean.getClass().getMethod("getCpuLoad");
            m.setAccessible(true);
            double load = ((Number) m.invoke(osBean)).doubleValue();
            return load < 0 ? 50.0 : load * 100.0;
        } catch (Exception e) {
            return 50.0;  // fallback: assume 50 % when API unavailable
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private synchronized void send(Message msg) {
        writer.print(msg.serialize());
        writer.flush();
    }

    private void shutdownPool() {
        if (threadPool == null) return;
        threadPool.shutdown();
        try {
            if (!threadPool.awaitTermination(30, TimeUnit.SECONDS)) {
                threadPool.shutdownNow();
            }
        } catch (InterruptedException e) {
            threadPool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private void workerBanner() {
        System.out.printf("[Worker-%d] ══════ PDC Grid Management — WORKER NODE ══════%n",
                workerId);
    }
}
