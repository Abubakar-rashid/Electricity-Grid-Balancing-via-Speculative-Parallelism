package com.gridmanagement;

import com.gridmanagement.master.MasterNode;
import com.gridmanagement.worker.WorkerNode;

/**
 * Unified entry point for the PDC Grid Management system.
 *
 * <p>The same JAR is used to launch either a master node or a worker node,
 * selected by the first command-line argument.
 *
 * <h3>Usage</h3>
 * <pre>
 * MASTER
 *   java -jar gridmanagement.jar master [workers] [nodes] [edges] [candidates] [chunkSize] [port]
 *
 * WORKER
 *   java -jar gridmanagement.jar worker [workerId] [masterHost] [masterPort]
 * </pre>
 *
 * <h3>Defaults</h3>
 * <table>
 *   <tr><th>Parameter</th><th>Default</th><th>Meaning</th></tr>
 *   <tr><td>workers</td>    <td>4</td>           <td>number of worker nodes to wait for</td></tr>
 *   <tr><td>nodes</td>      <td>500</td>          <td>grid nodes (V)</td></tr>
 *   <tr><td>edges</td>      <td>1000</td>         <td>directed transmission lines (E)</td></tr>
 *   <tr><td>candidates</td> <td>100 000</td>      <td>routing configurations to evaluate (N)</td></tr>
 *   <tr><td>chunkSize</td>  <td>500</td>          <td>candidates per dynamic-assignment chunk</td></tr>
 *   <tr><td>port</td>       <td>9090</td>         <td>TCP port for master ↔ worker comms</td></tr>
 * </table>
 *
 * <h3>Quick-start example (all on localhost, four separate terminals)</h3>
 * <pre>
 * Terminal 1 (Master):
 *   java -jar gridmanagement.jar master 4 500 1000 100000 500 9090
 *
 * Terminals 2–5 (Workers):
 *   java -jar gridmanagement.jar worker 1 localhost 9090
 *   java -jar gridmanagement.jar worker 2 localhost 9090
 *   java -jar gridmanagement.jar worker 3 localhost 9090
 *   java -jar gridmanagement.jar worker 4 localhost 9090
 * </pre>
 */
public class Main {

    // ── Defaults ──────────────────────────────────────────────────────────
    private static final int    DEF_WORKERS    = 4;
    private static final int    DEF_NODES      = 500;
    private static final int    DEF_EDGES      = 1_000;
    private static final int    DEF_CANDIDATES = 100_000;
    private static final int    DEF_CHUNK_SIZE = 500;
    private static final int    DEF_PORT       = 9090;
    private static final String DEF_HOST       = "localhost";

    // ─────────────────────────────────────────────────────────────────────

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            printUsage();
            return;
        }

        switch (args[0].toLowerCase()) {

            case "master" -> {
                int    workers    = intArg(args, 1, DEF_WORKERS);
                int    nodes      = intArg(args, 2, DEF_NODES);
                int    edges      = intArg(args, 3, DEF_EDGES);
                int    candidates = intArg(args, 4, DEF_CANDIDATES);
                int    chunkSize  = intArg(args, 5, DEF_CHUNK_SIZE);
                int    port       = intArg(args, 6, DEF_PORT);

                new MasterNode(port, workers, candidates, chunkSize)
                        .run(nodes, edges);
            }

            case "worker" -> {
                int    workerId   = intArg(args, 1, 1);
                String host       = args.length > 2 ? args[2] : DEF_HOST;
                int    port       = intArg(args, 3, DEF_PORT);

                // Start on a named thread so stack traces are easy to identify
                Thread t = new Thread(
                        new WorkerNode(workerId, host, port),
                        "WorkerNode-" + workerId);
                t.start();
                t.join();   // keep main thread alive until worker exits
            }

            default -> {
                System.err.println("Unknown mode: " + args[0]);
                printUsage();
                System.exit(1);
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private static int intArg(String[] args, int idx, int defaultVal) {
        if (idx < args.length) {
            try { return Integer.parseInt(args[idx]); }
            catch (NumberFormatException ignored) { /* fall through */ }
        }
        return defaultVal;
    }

    private static void printUsage() {
        System.out.println("""
            ╔══════════════════════════════════════════════════════════════╗
            ║  PDC Grid Management — Parallel Distributed Power-Flow OPF  ║
            ╚══════════════════════════════════════════════════════════════╝

            USAGE
              Master:  java -jar gridmanagement.jar master [workers] [nodes] [edges] [candidates] [chunkSize] [port]
              Worker:  java -jar gridmanagement.jar worker [workerId] [masterHost] [masterPort]

            DEFAULTS
              workers    = 4          number of worker nodes to wait for
              nodes      = 500        grid nodes (V)
              edges      = 1000       directed transmission lines (E)
              candidates = 100000     routing configs to evaluate (N)
              chunkSize  = 500        candidates per dynamic-assignment chunk
              port       = 9090       TCP port
              masterHost = localhost

            QUICK START  (four separate terminals)
              java -jar gridmanagement.jar master 4
              java -jar gridmanagement.jar worker 1 localhost 9090
              java -jar gridmanagement.jar worker 2 localhost 9090
              java -jar gridmanagement.jar worker 3 localhost 9090
              java -jar gridmanagement.jar worker 4 localhost 9090
            """);
    }
}
