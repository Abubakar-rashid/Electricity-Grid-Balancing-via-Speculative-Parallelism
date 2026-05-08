# Electricity Grid Balancing via Speculative Parallelism

This project is a Java 17 / Maven implementation of a speculative parallelism workload for power-grid balancing. It evaluates many route candidates against a grid model, compares sequential and distributed execution, and records the resulting performance and correctness data.

## What The Project Does

The system builds or loads a grid, generates candidate routing plans, and scores them with a cost function that reflects grid balance quality. A master process runs the sequential baseline, then distributes work to workers over TCP and collects the best result from the parallel run.

The implementation is designed to answer two questions:

1. Does the parallel version produce the same best candidate as the sequential baseline?
2. Does the overhead of distribution pay off as the number of candidates grows?

## Core Features

- Deterministic grid and candidate generation for repeatable experiments
- Optional custom grid input via [sample_grid.json](sample_grid.json)
- Sequential baseline plus distributed parallel execution
- Master-worker communication over a simple text protocol
- Per-worker multithreading using `ExecutorService`
- CSV result output for later comparison and plotting
- Python scripts for graph generation and result comparison

## Repository Layout

- [src/main/java/com/gridmanagement/Main.java](src/main/java/com/gridmanagement/Main.java) - command-line entry point
- [src/main/java/com/gridmanagement/master/](src/main/java/com/gridmanagement/master/) - master coordination and task dispatch
- [src/main/java/com/gridmanagement/worker/](src/main/java/com/gridmanagement/worker/) - worker execution and chunk processing
- [src/main/java/com/gridmanagement/grid/](src/main/java/com/gridmanagement/grid/) - grid generation and loading utilities
- [src/main/java/com/gridmanagement/model/](src/main/java/com/gridmanagement/model/) - candidate, result, and snapshot models
- [src/main/java/com/gridmanagement/protocol/](src/main/java/com/gridmanagement/protocol/) - message definitions and serialization
- [results.csv](results.csv) and related CSV files - benchmark outputs
- [plots/](plots/) - generated charts and summary text files

## Build

The project targets Java 17 and uses Maven. Build the runnable shaded JAR with:

```powershell
mvn -DskipTests package
```

The shaded artifact is produced in `target/` and uses `com.gridmanagement.Main` as the main class.

## Run

The application supports two modes: `master` and `worker`.

### Master

```powershell
java -jar target/gridmanagement-1.0-SNAPSHOT.jar master <workers> <nodes> <edges> <candidates> <chunkSize> <port>
```

Parameters:

- `workers` - expected number of worker processes
- `nodes` - number of grid nodes to generate
- `edges` - number of grid edges to generate
- `candidates` - number of route candidates to evaluate
- `chunkSize` - base chunk size for task distribution
- `port` - TCP port used for worker connections

Example:

```powershell
java -jar target/gridmanagement-1.0-SNAPSHOT.jar master 8 500 1000 100000 500 9090
```

### Worker

```powershell
java -jar target/gridmanagement-1.0-SNAPSHOT.jar worker <workerId> <host> <port>
```

Example:

```powershell
java -jar target/gridmanagement-1.0-SNAPSHOT.jar worker 1 localhost 9090
```

## Experimental Workflow

The intended workflow is:

1. Build the JAR.
2. Start the master with the desired number of workers and workload size.
3. Launch matching worker processes on the same host or across multiple hosts.
4. Let the master run the sequential baseline and then the parallel evaluation.
5. Collect the generated CSV output and compare it with the existing baseline and optimized results.

The repository includes existing experiment artifacts such as `results_baseline.csv`, `results_optimized.csv`, `weak_results.csv`, and multiple plots in `plots/`.

## Custom Grid Input

[sample_grid.json](sample_grid.json) is a ready-made custom grid template that you can edit to model your own network. It contains three sections:

- `nodes` - node IDs, names, and demand values
- `edges` - directed transmission links with capacity and impedance
- `generators` - generator locations with output and capacity

This file is useful when you want to evaluate the algorithm on a fixed grid topology instead of the synthetic generator. It gives you a simple way to test different demand patterns, generation placement, and edge constraints without changing the Java code.

## Output Files

The master writes result summaries to CSV after a run completes. The repo keeps several tracked output files for comparison:

- `results.csv`
- `results_baseline.csv`
- `results_optimized.csv`
- `weak_results.csv`

The `plots/` directory contains generated graphs and short text summaries that document the observed speedup, efficiency, weak scaling, and optimization impact.

## Notes

- The main class is `com.gridmanagement.Main`
- The project targets Java 17
- Ports should match between master and workers
- The sample grid file is the easiest place to start if you want to create your own grid model
