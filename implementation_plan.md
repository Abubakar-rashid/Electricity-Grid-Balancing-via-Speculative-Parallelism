# PDC Grid Management — Critical Analysis & Fix Plan

## Critical Issues Found

After reading every source file, here are **all bugs** causing "doesn't work, doesn't run, doesn't save results":

---

## 🔴 BUG 1 — `run.ps1` never compiles the project (FATAL)

`run.ps1` only **checks** whether `target\classes` or the JAR exists. It never calls `mvn package`.  
On a fresh clone, neither exists → the script prints `"Compile the project first"` and **exits immediately**.  
This is the #1 reason it doesn't run.

**Fix:** Add `mvn package -q -DskipTests` at the top of `run.ps1` (and all other scripts).

---

## 🔴 BUG 2 — Master output (including `results.csv`) goes to a hidden/closed window

`Start-Process java ...` without `-NoNewWindow` opens a **new console window** for the master.  
When the master finishes and the Java process exits, **that window closes** and all terminal output is lost.  
`results.csv` is written relative to the Java process's working directory — the script sets `-WorkingDirectory $PSScriptRoot`, which is correct, BUT if any path resolves differently, the file is written to a wrong location.

**Fix:** Redirect master stdout/stderr to a log file in `$PSScriptRoot`, then `cat` it after the process finishes so output is visible in the calling shell.

---

## 🔴 BUG 3 — Sequential baseline completely hangs with no output

`MasterNode.runSequentialBaseline()` evaluates **all N candidates** (default: 100,000) single-threaded on the master **before any worker connects**. For 100K candidates × 1000 edges, this takes **minutes** with zero console output. Users see a blank screen and think it's frozen.

**Fix:** Print a progress line every 10% during the sequential baseline.

---

## 🟡 BUG 4 — `results.csv` header gets written twice in experiment runs

`run_experiments.ps1` writes the CSV header to `results.csv` itself (line 32), then calls `run.ps1` which launches the master. The master's `printResult()` also checks `if (fcsv.length() == 0)` to write the header — but because `run_experiments.ps1` deletes `results.csv` before each individual run (line 28), the file is empty when the master opens it, so the master writes the header again. Then `run_experiments.ps1` re-reads and appends the file (including that duplicate header) to the accumulator file.

**Fix:** Remove the manual header-write from `run_experiments.ps1` and let the master own the header.

---

## 🟡 BUG 5 — Port mismatch between scripts

`run.ps1` uses port **9090** by default.  
`run_experiments.ps1`, `run_baseline_experiments.ps1`, and `run_optimized_experiments.ps1` use port **9099**.  
Running any experiment script right after `run.ps1` will conflict if the first run's port is still in `TIME_WAIT` state.

**Fix:** Standardise all scripts to one port (9090) and add `ss.setReuseAddress(true)` (already present in MasterNode — good).

---

## 🟡 BUG 6 — Stale template file `com/example/Main.java`

`src/main/java/com/example/Main.java` is a 125-byte Maven archetype leftover. It adds nothing and pollutes the source tree.

**Fix:** Delete the file and its package directory.

---

## 🟡 BUG 7 — `WorkerProxy.send()` missing `\n` terminator (latent)

`writer.print(msg.serialize())` is called; `serialize()` appends `\n`. The `PrintWriter` is created with `autoFlush=true`, but Java's `PrintWriter` only auto-flushes on `println()`/`printf()`/`format()` — not on `print()`. The explicit `writer.flush()` call saves it. **However**, if a flush is ever missed this would silently stall the protocol. 

**Fix:** Change `writer.print(msg.serialize())` → `writer.println(msg.serialize().stripTrailing())` in both `WorkerProxy` and `WorkerNode` to use the auto-flush trigger correctly.

---

## 🟡 BUG 8 — `run_experiments.ps1` uses `run.ps1` as a subprocess

`run_experiments.ps1` calls `& powershell -File .\run.ps1` — a **second PowerShell** process. This doubles startup overhead and makes error detection fragile. The sub-`run.ps1` also opens master/worker in **yet more windows**, so CSV files end up written from multiple Java processes, corrupting the accumulator.

**Fix:** Inline the master/worker launch logic directly in `run_experiments.ps1` instead of delegating to `run.ps1`.

---

## Open Questions

> [!IMPORTANT]  
> Should the sequential baseline be **kept** (it's needed for Speedup = T_seq / T_par metrics)?  
> Currently it runs 100K candidates before workers connect. Should we reduce the default, or just add progress output?

> [!IMPORTANT]
> Should all experiment scripts delete `results.csv` at start (fresh run each time) or accumulate?

---

## Proposed Changes

### Scripts

#### [MODIFY] run.ps1
- Add `mvn package -q -DskipTests` build step at top
- Redirect master stdout/stderr to `master.log` in `$PSScriptRoot`  
- After `Wait-Process`, `cat` the log file so output appears in calling terminal  
- Fix port to 9090 consistently

#### [MODIFY] run_experiments.ps1
- Add build step at top  
- Remove manual CSV header write (master owns the header)  
- Inline master/worker launch (no subprocess `run.ps1`)  
- Fix port to 9090

#### [MODIFY] run_baseline_experiments.ps1
- Add build step at top  
- Fix port to 9090

#### [MODIFY] run_optimized_experiments.ps1  
- Add build step at top  
- Fix port to 9090

---

### Java Sources

#### [MODIFY] MasterNode.java
- Add `System.out.printf` progress every 10% in `runSequentialBaseline()`
- Write `results.csv` to **absolute path** (`System.getProperty("user.dir")`) to remove ambiguity

#### [MODIFY] WorkerProxy.java
- Change `writer.print(msg.serialize())` → `writer.println(msg.serialize().stripTrailing())` and remove redundant `flush()`

#### [MODIFY] WorkerNode.java
- Same send fix as WorkerProxy

#### [DELETE] src/main/java/com/example/Main.java
- Dead template file, remove entirely

---

## Verification Plan

### Automated
1. `mvn package -DskipTests` — must compile cleanly with zero errors
2. `.\run.ps1 -Workers 2 -Candidates 5000` — must:
   - Complete without error
   - Print the results banner to console  
   - Write `results.csv` in the project root
3. `.\run_experiments.ps1 -WorkersStr "1,2" -CandidatesStr "1000,5000"` — must produce a valid multi-row `results.csv`
4. `python plot_results.py` — must generate plots without error

### Manual
- Confirm `results.csv` appears in the project root after each run  
- Confirm speedup > 1.0 for 2+ workers  
- Confirm correctness column shows `true`
