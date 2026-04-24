package com.gridmanagement.master;

import com.gridmanagement.grid.GridGenerator;
import com.gridmanagement.model.GridSnapshot;
import com.gridmanagement.model.RouteResult;
import com.gridmanagement.protocol.Message;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Master node — coordinates the distributed power-flow optimisation.
 *
 * <h3>Lifecycle</h3>
 * <ol>
 *   <li>Build the synthetic grid snapshot.</li>
 *   <li>Partition [0, N) into fixed-size chunks and push them onto a
 *       {@link ConcurrentLinkedQueue} (the "task queue").</li>
 *   <li>Open a TCP server socket and wait for exactly K workers.</li>
 *   <li>Broadcast {@code GRID_INIT} to every connected worker.</li>
 *   <li>Workers drive the dynamic-assignment loop themselves:
 *       each worker sends {@code TASK_REQUEST} and the corresponding
 *       {@link WorkerProxy} dequeues and dispatches a {@code TASK_ASSIGN}.
 *       After returning a {@code RESULT_RETURN} the proxy immediately
 *       dispatches the next chunk (if any) without waiting for another
 *       explicit request.</li>
 *   <li>A {@link CountDownLatch} blocks the master's main thread until every
 *       chunk has been acknowledged.  Then the global optimum is printed and
 *       {@code SHUTDOWN} is broadcast.</li>
 * </ol>
 *
 * <h3>Thread safety</h3>
 * <ul>
 *   <li>{@code taskQueue} – {@link ConcurrentLinkedQueue}, lock-free.</li>
 *   <li>{@code globalBest} – {@link AtomicReference} updated via CAS loop.</li>
 *   <li>{@code allResults} – protected by {@code resultsLock}
 *       ({@link ReentrantLock}).</li>
 *   <li>{@code chunksCompleted} – {@link AtomicInteger}; drives the latch.</li>
 * </ul>
 */
public class MasterNode {

    // ── Configuration ─────────────────────────────────────────────────────
    private final int port;
    private final int expectedWorkers;
    private final int totalCandidates;
    private final int chunkSize;
    private final int totalChunks;

    // ── Grid ──────────────────────────────────────────────────────────────
    private GridSnapshot grid;

    // ── Task queue ────────────────────────────────────────────────────────
    /** Each element is {chunkId, rangeStart, rangeEnd}. */
    private final ConcurrentLinkedQueue<int[]> taskQueue = new ConcurrentLinkedQueue<>();

    // ── Result aggregation ────────────────────────────────────────────────
    private final AtomicInteger chunksCompleted = new AtomicInteger(0);
    private final AtomicReference<RouteResult> globalBest =
            new AtomicReference<>(RouteResult.WORST);
    private final ReentrantLock resultsLock = new ReentrantLock();
    private final List<RouteResult> allResults = new ArrayList<>();

    // ── Synchronisation ───────────────────────────────────────────────────
    private final CountDownLatch doneLatch;

    // ── Timing ────────────────────────────────────────────────────────────
    private long evalStartTime;

    // ── Worker proxies ────────────────────────────────────────────────────
    private final List<WorkerProxy> proxies = new ArrayList<>();

    // ─────────────────────────────────────────────────────────────────────

    public MasterNode(int port, int expectedWorkers, int totalCandidates, int chunkSize) {
        this.port             = port;
        this.expectedWorkers  = expectedWorkers;
        this.totalCandidates  = totalCandidates;
        this.chunkSize        = chunkSize;
        this.totalChunks      = (int) Math.ceil((double) totalCandidates / chunkSize);
        this.doneLatch        = new CountDownLatch(totalChunks);
    }

    // ── Public entry ──────────────────────────────────────────────────────

    public void run(int nodeCount, int edgeCount) throws IOException, InterruptedException {
        banner();
        System.out.printf("[MASTER] Config: %d nodes  %d edges  %d candidates" +
                          "  %d chunks of %d  port=%d%n",
                nodeCount, edgeCount, totalCandidates, totalChunks, chunkSize, port);

        // ── Step 1: Build grid ────────────────────────────────────────────
        System.out.println("[MASTER] Building grid...");
        long t0 = System.currentTimeMillis();
        grid = GridGenerator.generateGrid(nodeCount, edgeCount);
        System.out.printf("[MASTER] Grid built in %d ms%n",
                System.currentTimeMillis() - t0);

        // ── Step 2: Populate task queue ───────────────────────────────────
        int chunkId = 0;
        for (int start = 0; start < totalCandidates; start += chunkSize) {
            int end = Math.min(start + chunkSize, totalCandidates);
            taskQueue.add(new int[]{chunkId++, start, end});
        }
        System.out.printf("[MASTER] Task queue ready: %d chunks%n", totalChunks);

        // ── Step 3: Accept workers ────────────────────────────────────────
        System.out.printf("[MASTER] Waiting for %d worker(s) on port %d...%n",
                expectedWorkers, port);
        try (ServerSocket ss = new ServerSocket(port)) {
            ss.setReuseAddress(true);
            for (int i = 0; i < expectedWorkers; i++) {
                Socket socket = ss.accept();
                WorkerProxy proxy = new WorkerProxy(socket, this);
                proxies.add(proxy);
                Thread t = new Thread(proxy, "WorkerProxy-" + i);
                t.setDaemon(true);
                t.start();
                System.out.printf("[MASTER] Worker connection %d/%d accepted from %s%n",
                        i + 1, expectedWorkers,
                        socket.getInetAddress().getHostAddress());
            }

            // ── Step 4: Broadcast GRID_INIT ───────────────────────────────
            evalStartTime = System.currentTimeMillis();
            System.out.println("[MASTER] Broadcasting GRID_INIT to all workers...");
            String serialisedGrid = grid.serialize();
            for (WorkerProxy p : proxies) {
                p.send(Message.gridInit(totalCandidates, serialisedGrid));
            }

            // ── Step 5: Block until all chunks processed ──────────────────
            System.out.println("[MASTER] Evaluation running — waiting for results...");
            doneLatch.await();
        }

        long totalTime = System.currentTimeMillis() - evalStartTime;

        // ── Step 6: Print global result ───────────────────────────────────
        RouteResult best = globalBest.get();
        printResult(best, totalTime);

        // ── Step 7: Broadcast SHUTDOWN ────────────────────────────────────
        System.out.println("[MASTER] Sending SHUTDOWN to all workers...");
        for (WorkerProxy p : proxies) {
            try { p.send(Message.shutdown()); } catch (Exception ignored) {}
        }
        System.out.println("[MASTER] Done.");
    }

    // ── Callbacks invoked by WorkerProxy threads ──────────────────────────

    /**
     * Called by a {@link WorkerProxy} when it receives a {@code TASK_REQUEST}
     * or when it wants to push the next chunk after a {@code RESULT_RETURN}.
     * Dequeues one chunk and sends {@code TASK_ASSIGN}; does nothing if the
     * queue is empty (worker will wait for {@code SHUTDOWN}).
     */
    public void dispatchNextChunk(WorkerProxy proxy) {
        int[] chunk = taskQueue.poll();
        if (chunk != null) {
            proxy.send(Message.taskAssign(chunk[0], chunk[1], chunk[2]));
        }
        // Queue exhausted → proxy waits for SHUTDOWN
    }

    /**
     * Called by a {@link WorkerProxy} when it receives a {@code RESULT_RETURN}.
     * Updates the global best via a CAS loop; counts down the completion latch.
     */
    public void acceptResult(RouteResult result) {
        // CAS loop: update globalBest only when result is strictly better
        RouteResult cur;
        do {
            cur = globalBest.get();
        } while (result.costScore < cur.costScore
                 && !globalBest.compareAndSet(cur, result));

        resultsLock.lock();
        try { allResults.add(result); }
        finally { resultsLock.unlock(); }

        int done = chunksCompleted.incrementAndGet();
        doneLatch.countDown();

        // Progress logging every 5 % or on the last chunk
        if (done == totalChunks || done % Math.max(1, totalChunks / 20) == 0) {
            System.out.printf("[MASTER] Progress: %4d / %d  (%.0f%%)  " +
                              "best cost so far: %.2f%n",
                    done, totalChunks,
                    100.0 * done / totalChunks,
                    globalBest.get().costScore);
        }
    }

    /** Called by a {@link WorkerProxy} when it receives a {@code STATUS_UPDATE}. */
    public void handleStatus(int workerId, double progressPct, double cpuLoadPct) {
        System.out.printf("[STATUS] Worker-%-2d  progress=%.1f%%  cpu=%.1f%%%n",
                workerId, progressPct, cpuLoadPct);
    }

    // ── Private helpers ───────────────────────────────────────────────────

    private void printResult(RouteResult best, long totalMs) {
        int w = 62;
        String line = "═".repeat(w);
        System.out.println("\n╔" + line + "╗");
        System.out.printf( "║  %-" + (w - 2) + "s║%n", "EVALUATION COMPLETE");
        System.out.println("╠" + line + "╣");
        System.out.printf( "║  Global Optimum  Candidate #%-6d                       ║%n",
                best.candidateId);
        System.out.printf( "║  Cost Score      %-12.2f                           ║%n",
                best.costScore);
        System.out.printf( "║  Feasible        %-5b                                 ║%n",
                best.feasible);
        System.out.printf( "║  Found by Worker %-3d                                   ║%n",
                best.workerId);
        System.out.printf( "║  Total Time      %d ms                                  ║%n",
                totalMs);
        System.out.printf( "║  Workers         %-3d                                   ║%n",
                expectedWorkers);
        System.out.printf( "║  Chunks Done     %d / %d                               ║%n",
                chunksCompleted.get(), totalChunks);
        System.out.println("╚" + line + "╝\n");
    }

    private static void banner() {
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║     PDC Grid Management  ─  MASTER NODE                     ║");
        System.out.println("║     Parallel Distributed Power-Flow Optimiser                ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝");
    }
}
