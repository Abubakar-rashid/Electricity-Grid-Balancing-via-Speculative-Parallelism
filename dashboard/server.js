import { createServer } from 'http';
import { readFile, writeFile } from 'fs/promises';
import { existsSync, createReadStream } from 'fs';
import { spawn } from 'child_process';
import path from 'path';
import { fileURLToPath } from 'url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const projectRoot = path.resolve(__dirname, '..');
const publicDir = path.join(__dirname, 'public');
const jarPath = path.join(projectRoot, 'target', 'gridmanagement-1.0-SNAPSHOT.jar');
const resultsPath = path.join(projectRoot, 'results.csv');
const detailsPath = path.join(projectRoot, 'run_details.json');
const baselinePath = path.join(projectRoot, 'results_baseline.csv');
const optimizedPath = path.join(projectRoot, 'results_optimized.csv');
const weakPath = path.join(projectRoot, 'weak_results.csv');
const gridPath = path.join(projectRoot, 'sample_grid.json');
const logDir = path.join(projectRoot, 'dashboard-logs');

const state = {
  running: false,
  phase: null,
  startedAt: null,
  endedAt: null,
  config: null,
  baselineSummary: null,
  parallelSummary: null,
  runCommands: [],
  runDetails: null,
  master: null,
  workers: [],
  logs: [],
  resultText: '',
  lastRunSummary: null,
};

async function ensureLogDir() {
  try {
    await writeFile(path.join(logDir, '.keep'), '', { flag: 'a' });
  } catch {
    // lazily create the directory on demand
  }
}

async function isPortAvailable(port) {
  return await new Promise((resolve) => {
    const probe = createServer();
    probe.unref();
    probe.once('error', () => resolve(false));
    probe.listen(port, '::', () => {
      probe.close(() => resolve(true));
    });
  });
}

async function findAvailablePort(startPort) {
  for (let port = startPort; port < startPort + 20; port += 1) {
    if (await isPortAvailable(port)) {
      return port;
    }
  }

  throw new Error(`No free port found starting at ${startPort}.`);
}

function addLog(source, line) {
  const entry = { time: new Date().toISOString(), source, line };
  state.logs.push(entry);
  if (state.logs.length > 500) state.logs.shift();
}

function parseRunSummary(logs) {
  const summary = {
    globalOptimum: null,
    costScore: null,
    feasible: null,
    foundByWorker: null,
    seqTimeMs: null,
    parTimeMs: null,
    speedup: null,
    efficiency: null,
    totalTimeMs: null,
    workers: null,
    chunksDone: null,
    config: null,
  };

  const lines = logs.map((entry) => entry.line);
  const getValueAfter = (label) => {
    const index = lines.findIndex((line) => line.includes(label));
    if (index < 0 || index + 1 >= lines.length) return null;
    return lines[index + 1].trim();
  };

  for (const line of lines) {
    if (line.includes('Global Optimum')) {
      const match = line.match(/Candidate #\s*(\d+)/i);
      if (match) summary.globalOptimum = Number(match[1]);
    }
    if (line.includes('Cost Score')) {
      const match = line.match(/Cost Score\s*([0-9]+(?:\.[0-9]+)?)/i);
      if (match) summary.costScore = Number(match[1]);
    }
    if (line.includes('Feasible')) {
      summary.feasible = /true/i.test(line);
    }
    if (line.includes('Found by Worker')) {
      const match = line.match(/Found by Worker\s*(\d+)/i);
      if (match) summary.foundByWorker = Number(match[1]);
    }
    if (line.includes('Total Time(Par)')) {
      const match = line.match(/Total Time\(Par\)\s*(\d+)\s*ms/i);
      if (match) summary.parTimeMs = Number(match[1]);
    }
    if (line.includes('Total Time(Seq)')) {
      const match = line.match(/Total Time\(Seq\)\s*(\d+)\s*ms/i);
      if (match) summary.seqTimeMs = Number(match[1]);
    }
    if (line.includes('Total Time') && !line.includes('Total Time(Par)') && !line.includes('Total Time(Seq)')) {
      const match = line.match(/Total Time\s*(\d+)\s*ms/i);
      if (match) summary.totalTimeMs = Number(match[1]);
    }
    if (line.includes('Workers')) {
      const match = line.match(/Workers\s*(\d+)/i);
      if (match) summary.workers = Number(match[1]);
    }
    if (line.includes('Chunks Done')) {
      const match = line.match(/Chunks Done\s*(\d+)\s*\/\s*(\d+)/i);
      if (match) summary.chunksDone = `${match[1]} / ${match[2]}`;
    }
    if (line.includes('[MASTER] Config:')) {
      const match = line.match(/Config:\s*(\d+) nodes\s*(\d+) edges\s*(\d+) candidates\s*(\d+) chunks of (\d+)\s*port=(\d+)/i);
      if (match) {
        summary.config = {
          nodes: Number(match[1]),
          edges: Number(match[2]),
          candidates: Number(match[3]),
          chunkSize: Number(match[4]),
          workers: Number(match[5]),
          port: Number(match[6]),
        };
      }
    }
  }

  const totalTimeFallback = getValueAfter('Total Time');
  if (summary.totalTimeMs === null && totalTimeFallback) {
    const match = totalTimeFallback.match(/(\d+)\s*ms/);
    if (match) summary.totalTimeMs = Number(match[1]);
  }

  if (summary.parTimeMs === null && summary.totalTimeMs !== null) {
    summary.parTimeMs = summary.totalTimeMs;
  }

  if (summary.seqTimeMs !== null && summary.parTimeMs !== null && summary.parTimeMs > 0) {
    summary.speedup = summary.seqTimeMs / summary.parTimeMs;
    if (summary.workers && summary.workers > 0) {
      summary.efficiency = summary.speedup / summary.workers;
    }
  }

  return summary;
}

function sendJson(res, statusCode, data) {
  res.writeHead(statusCode, { 'Content-Type': 'application/json; charset=utf-8' });
  res.end(JSON.stringify(data));
}

function sendText(res, statusCode, text, contentType = 'text/plain; charset=utf-8') {
  res.writeHead(statusCode, { 'Content-Type': contentType });
  res.end(text);
}

function parseCsv(text) {
  const lines = text.trim().split(/\r?\n/).filter(Boolean);
  if (!lines.length) return [];
  const headers = lines[0].split(',');
  return lines.slice(1).map((line) => {
    const values = line.split(',');
    return Object.fromEntries(headers.map((header, index) => [header, values[index] ?? '']));
  });
}

async function readCsvIfExists(filePath) {
  if (!existsSync(filePath)) return [];
  return parseCsv(await readFile(filePath, 'utf8'));
}

async function loadGridData() {
  if (!existsSync(gridPath)) return null;
  const text = await readFile(gridPath, 'utf8');
  return JSON.parse(text);
}

async function loadRunDetails() {
  if (!existsSync(detailsPath)) return null;
  const text = await readFile(detailsPath, 'utf8');
  return JSON.parse(text);
}

function startProcess(command, args, name, envOverrides = {}) {
  const child = spawn(command, args, {
    cwd: projectRoot,
    windowsHide: true,
    shell: false,
    env: { ...process.env, ...envOverrides },
  });

  let stdoutBuffer = '';
  let stderrBuffer = '';

  const flushBuffer = (buffer, sourceName) => {
    const lines = buffer.split(/\r?\n/);
    return {
      remainder: lines.pop() ?? '',
      lines: lines.filter(Boolean).map((line) => ({ sourceName, line })),
    };
  };

  child.stdout.on('data', (chunk) => {
    stdoutBuffer += chunk.toString();
    const flushed = flushBuffer(stdoutBuffer, name);
    stdoutBuffer = flushed.remainder;
    flushed.lines.forEach(({ sourceName, line }) => addLog(sourceName, line));
  });

  child.stderr.on('data', (chunk) => {
    stderrBuffer += chunk.toString();
    const flushed = flushBuffer(stderrBuffer, name);
    stderrBuffer = flushed.remainder;
    flushed.lines.forEach(({ sourceName, line }) => addLog(sourceName, line));
  });

  child.on('exit', (code) => {
    if (stdoutBuffer.trim()) addLog(name, stdoutBuffer.trim());
    if (stderrBuffer.trim()) addLog(name, stderrBuffer.trim());
    addLog(name, `Process exited with code ${code}`);
  });

  return child;
}

function buildCommandString(config) {
  return ['java', '-jar', `"${jarPath}"`, 'master', String(config.workers), String(config.nodes), String(config.edges), String(config.candidates), String(config.chunkSize), String(config.port)].join(' ');
}

async function runPhase(label, config) {
  // Find an available port for the master-worker communication
  const actualPort = await findAvailablePort(config.port);
  const phaseConfig = { ...config, port: actualPort };
  
  const command = buildCommandString(phaseConfig);
  const stageStartIndex = state.logs.length;
  state.phase = label;
  state.runCommands.push({ label, command, config: phaseConfig });
  addLog('dashboard', `[${label}] Command: ${command}`);
  addLog('dashboard', `[${label}] Starting master for ${phaseConfig.workers} worker(s) on port ${actualPort}`);

  const masterArgs = ['-jar', jarPath, 'master', String(phaseConfig.workers), String(phaseConfig.nodes), String(phaseConfig.edges), String(phaseConfig.candidates), String(phaseConfig.chunkSize), String(actualPort)];
  // When the dashboard spawns the master, avoid polluting the repo-level
  // `results.csv` file — tell the Java process to skip writing it.
  const master = startProcess('java', masterArgs, `${label}-master`, { SKIP_RESULTS_CSV: '1' });
  state.master = master;

  await new Promise((resolve) => setTimeout(resolve, 1000));

  const workers = [];
  for (let workerId = 1; workerId <= phaseConfig.workers; workerId += 1) {
    const workerArgs = ['-jar', jarPath, 'worker', String(workerId), 'localhost', String(actualPort)];
    const worker = startProcess('java', workerArgs, `${label}-worker-${workerId}`);
    workers.push(worker);
  }
  state.workers = workers;

  await new Promise((resolve) => {
    master.once('close', resolve);
  });

  try {
    if (existsSync(resultsPath)) {
      state.resultText = await readFile(resultsPath, 'utf8');
    }
  } catch {
    // ignore read errors here; the UI can refresh later
  }

  let details = null;
  try {
    details = await loadRunDetails();
  } catch {
    details = null;
  }

  const stageLogs = state.logs.slice(stageStartIndex);
  const parsed = parseRunSummary(stageLogs);
  // If parsing failed to discover the candidate id etc., fall back to
  // values exported in run_details.json (written by the master). This
  // improves robustness when log formatting differs or buffering hides
  // particular lines.
  if ((!parsed.globalOptimum || parsed.globalOptimum === null) && details && details.summary && details.summary.candidateId) {
    parsed.globalOptimum = details.summary.candidateId;
    parsed.costScore = details.summary.costScore ?? parsed.costScore;
    parsed.feasible = details.summary.feasible ?? parsed.feasible;
    parsed.foundByWorker = details.summary.workerId ?? parsed.foundByWorker;
    parsed.parTimeMs = details.summary.parTimeMs ?? parsed.parTimeMs;
    parsed.seqTimeMs = details.summary.seqTimeMs ?? parsed.seqTimeMs;
  }

  // For phase summaries, always use parTimeMs as the canonical totalTimeMs
  // (it represents the actual wall-clock time for that phase).
  if (parsed.parTimeMs != null) {
    parsed.totalTimeMs = parsed.parTimeMs;
  } else if ((parsed.totalTimeMs === null || parsed.totalTimeMs === undefined) && parsed.seqTimeMs != null) {
    parsed.totalTimeMs = parsed.seqTimeMs;
  }

  // Recalculate speedup based on correct totalTimeMs
  if (parsed.seqTimeMs != null && parsed.totalTimeMs != null && parsed.totalTimeMs > 0) {
    parsed.speedup = parsed.seqTimeMs / parsed.totalTimeMs;
    if (parsed.workers != null && parsed.workers > 0) {
      parsed.efficiency = parsed.speedup / parsed.workers;
    }
  }

  const summary = {
    ...parsed,
    config: phaseConfig,
    label,
    command,
  };

  return { summary, details, stageLogs };
}

async function startRun(body) {
  if (state.running) {
    throw new Error('A run is already in progress.');
  }

  if (!existsSync(jarPath)) {
    throw new Error('Could not find target/gridmanagement-1.0-SNAPSHOT.jar. Build the Java project first.');
  }

  const config = {
    workers: Number(body.workers ?? 8),
    nodes: Number(body.nodes ?? 500),
    edges: Number(body.edges ?? 1000),
    candidates: Number(body.candidates ?? 10000),
    chunkSize: Number(body.chunkSize ?? 500),
    port: Number(body.port ?? 9090),
  };

  state.running = true;
  state.startedAt = new Date().toISOString();
  state.endedAt = null;
  state.config = config;
  state.phase = 'baseline';
  state.baselineSummary = null;
  state.parallelSummary = null;
  state.runCommands = [];
  state.runDetails = null;
  state.logs = [];
  state.resultText = '';

  const baselineConfig = { ...config, workers: 1 };

  try {
    const baseline = await runPhase('baseline', baselineConfig);
    state.baselineSummary = baseline.summary;
    state.runDetails = baseline.details ?? state.runDetails;
    addLog('dashboard', 'Baseline run complete. Starting parallel run next.');

    const parallel = await runPhase('parallel', config);
    state.parallelSummary = parallel.summary;
    state.runDetails = parallel.details ?? state.runDetails;
    state.lastRunSummary = parallel.summary;
    state.running = false;
    state.phase = null;
    state.endedAt = new Date().toISOString();
    addLog('dashboard', 'Run completed.');
  } catch (error) {
    state.running = false;
    state.phase = null;
    state.endedAt = new Date().toISOString();
    addLog('dashboard', `Run failed: ${error.message}`);
    throw error;
  }
}

const server = createServer(async (req, res) => {
  const url = new URL(req.url, 'http://localhost');
  const pathname = url.pathname;

  try {
    if (req.method === 'GET' && pathname === '/') {
      const html = await readFile(path.join(publicDir, 'index.html'), 'utf8');
      return sendText(res, 200, html, 'text/html; charset=utf-8');
    }

    if (req.method === 'GET' && pathname === '/app.js') {
      const filePath = path.join(publicDir, 'app.js');
      res.writeHead(200, { 'Content-Type': 'text/javascript; charset=utf-8' });
      return createReadStream(filePath).pipe(res);
    }

    if (req.method === 'GET' && pathname === '/styles.css') {
      const filePath = path.join(publicDir, 'styles.css');
      res.writeHead(200, { 'Content-Type': 'text/css; charset=utf-8' });
      return createReadStream(filePath).pipe(res);
    }

    if (req.method === 'GET' && pathname === '/api/data') {
      const [results, baseline, optimized, weak, grid] = await Promise.all([
        readCsvIfExists(resultsPath),
        readCsvIfExists(baselinePath),
        readCsvIfExists(optimizedPath),
        readCsvIfExists(weakPath),
        loadGridData(),
      ]);

      return sendJson(res, 200, { results, baseline, optimized, weak, grid, state });
    }

    if (req.method === 'GET' && pathname === '/api/status') {
      return sendJson(res, 200, state);
    }

    if (req.method === 'POST' && pathname === '/api/run') {
      let raw = '';
      for await (const chunk of req) raw += chunk;
      const body = raw ? JSON.parse(raw) : {};
      await startRun(body);
      return sendJson(res, 202, { ok: true });
    }

    if (req.method === 'POST' && pathname === '/api/stop') {
      state.master?.kill('SIGTERM');
      for (const worker of state.workers) worker?.kill('SIGTERM');
      state.running = false;
      state.endedAt = new Date().toISOString();
      addLog('dashboard', 'Stop requested.');
      return sendJson(res, 200, { ok: true });
    }

    if (req.method === 'GET' && pathname === '/api/logs') {
      return sendJson(res, 200, state.logs);
    }

    if (req.method === 'GET' && pathname === '/api/results') {
      return sendText(res, 200, state.resultText || (existsSync(resultsPath) ? await readFile(resultsPath, 'utf8') : ''));
    }

    if (req.method === 'GET' && pathname === '/api/sample-grid') {
      return sendJson(res, 200, await loadGridData());
    }

    return sendText(res, 404, 'Not found');
  } catch (error) {
    return sendJson(res, 500, { error: error.message });
  }
});

await ensureLogDir();

const requestedPort = Number(process.env.PORT ?? 4173);
const port = await findAvailablePort(requestedPort);

server.listen(port, () => {
  if (port !== requestedPort) {
    console.log(`Port ${requestedPort} was busy, using ${port} instead.`);
  }
  console.log(`Dashboard running at http://localhost:${port}`);
});
