const state = {
  data: null,
  running: false,
};

const runForm = document.getElementById('runForm');
const stopBtn = document.getElementById('stopBtn');
const logsEl = document.getElementById('logs');
const summaryCardsEl = document.getElementById('summaryCards');
const resultTableEl = document.getElementById('resultTable');
const comparisonEl = document.getElementById('comparisonCard');
const commandStackEl = document.getElementById('commandStack');
const runDetailsCardEl = document.getElementById('runDetailsCard');
const statusTextEl = document.getElementById('statusText');
const gridSvg = document.getElementById('gridSvg');

async function fetchJson(url, options) {
  const response = await fetch(url, options);
  if (!response.ok) {
    const body = await response.text();
    throw new Error(body || response.statusText);
  }
  return response.json();
}

function fmt(value) {
  if (value === null || value === undefined || value === '') return '—';
  const num = Number(value);
  if (Number.isFinite(num)) return num.toLocaleString();
  return String(value);
}

function renderSummary(summary) {
  if (!summary) {
    summaryCardsEl.innerHTML = '<div class="command-placeholder">No run completed yet.</div>';
    return;
  }

  summaryCardsEl.innerHTML = [
    { label: 'Global Optimum', value: summary.globalOptimum !== null ? `#${summary.globalOptimum}` : '—' },
    { label: 'Cost Score', value: summary.costScore !== null ? summary.costScore.toLocaleString(undefined, { maximumFractionDigits: 2 }) : '—' },
    { label: 'Workers', value: summary.workers ?? '—' },
    { label: 'Chunks Done', value: summary.chunksDone ?? '—' },
    { label: 'Feasible', value: summary.feasible === null ? '—' : String(summary.feasible) },
    { label: 'Found By', value: summary.foundByWorker !== null ? `Worker ${summary.foundByWorker}` : '—' },
  ].map(({ label, value }) => `
    <div class="metric">
      <div class="card-label">${label}</div>
      <div class="metric-value">${value}</div>
    </div>
  `).join('');
}

function renderCommandStack(state) {
  const commands = [];
  if (state?.baselineSummary?.command) {
    commands.push({ label: 'Baseline run', command: state.baselineSummary.command, note: '1-worker run executed first.' });
  }
  if (state?.parallelSummary?.command) {
    commands.push({ label: 'Parallel run', command: state.parallelSummary.command, note: 'Chosen worker count executed second.' });
  }

  commandStackEl.innerHTML = commands.length
    ? commands.map(({ label, command, note }) => `
      <div class="command-card">
        <div class="command-card-head">
          <span>${label}</span>
          <small>${note}</small>
        </div>
        <pre class="command-line">${command}</pre>
      </div>
    `).join('')
    : '<div class="command-placeholder">No run command yet.</div>';
}

function renderComparison(state) {
  const baseline = state?.baselineSummary;
  const parallel = state?.parallelSummary;
  const baselineTime = baseline?.totalTimeMs ?? null;
  const parallelTime = parallel?.totalTimeMs ?? null;
  const speedup = baselineTime !== null && parallelTime ? baselineTime / parallelTime : null;
  const improvement = speedup !== null ? `${speedup.toFixed(2)}x` : '—';

  comparisonEl.innerHTML = `
    <div class="comparison-grid">
      <div class="comparison-panel baseline">
        <div class="comparison-label">Baseline run</div>
        <div class="comparison-value">${baselineTime !== null ? `${baselineTime} ms` : '—'}</div>
        <div class="comparison-note">1-worker execution for the same nodes, edges, candidates, and chunk size.</div>
      </div>
      <div class="comparison-panel parallel">
        <div class="comparison-label">Parallel run</div>
        <div class="comparison-value">${parallelTime !== null ? `${parallelTime} ms` : '—'}</div>
        <div class="comparison-note">The requested worker count processes the same workload in distributed chunks.</div>
      </div>
      <div class="comparison-panel accent">
        <div class="comparison-label">Speedup</div>
        <div class="comparison-value">${improvement}</div>
        <div class="comparison-note">Baseline time divided by parallel time for the latest two-phase run.</div>
      </div>
    </div>
  `;
}

function renderRunDetails(details) {
  if (!details) {
    runDetailsCardEl.innerHTML = '<div class="command-placeholder">Run the dashboard once to populate the selected config details.</div>';
    return;
  }

  const summary = details.summary ?? {};
  const nodes = details.nodes ?? [];
  const edges = details.edges ?? [];
  const nodeRows = nodes.map((node) => `
    <tr>
      <td>${fmt(node.id)}</td>
      <td>${fmt(node.demand)}</td>
      <td>${fmt(node.generatorOutput)}</td>
      <td>${fmt(node.generatorCapacity)}</td>
      <td>${Number(node.netFlowMw ?? 0).toFixed(2)}</td>
    </tr>
  `).join('');

  const edgeRows = edges.map((edge) => `
    <tr>
      <td>${fmt(edge.id)}</td>
      <td>${fmt(edge.from)}</td>
      <td>${fmt(edge.to)}</td>
      <td>${fmt(edge.capacity)}</td>
      <td>${fmt(edge.impedance)}</td>
      <td>${Number(edge.flowMw ?? 0).toFixed(2)}</td>
    </tr>
  `).join('');

  runDetailsCardEl.innerHTML = `
    <div class="details-summary">
      <div class="details-pill">Candidate #${fmt(summary.candidateId)}</div>
      <div class="details-pill">Worker ${fmt(summary.workerId)}</div>
      <div class="details-pill">Cost ${Number(summary.costScore ?? 0).toLocaleString(undefined, { maximumFractionDigits: 2 })}</div>
      <div class="details-pill">Feasible ${String(summary.feasible ?? false)}</div>
      <div class="details-pill">Seq ${fmt(summary.seqTimeMs)} ms</div>
      <div class="details-pill">Par ${fmt(summary.parTimeMs)} ms</div>
    </div>

    <div class="details-grid">
      <div class="details-table-block">
        <h4>Node Setup</h4>
        <div class="details-table-wrap">
          <table>
            <thead>
              <tr><th>Node</th><th>Demand</th><th>Gen Out</th><th>Gen Cap</th><th>Net Flow</th></tr>
            </thead>
            <tbody>${nodeRows}</tbody>
          </table>
        </div>
      </div>

      <div class="details-table-block">
        <h4>Edge Flow Assignment</h4>
        <div class="details-table-wrap">
          <table>
            <thead>
              <tr><th>Edge</th><th>From</th><th>To</th><th>Capacity</th><th>Impedance</th><th>Flow MW</th></tr>
            </thead>
            <tbody>${edgeRows}</tbody>
          </table>
        </div>
      </div>
    </div>
  `;
}

function renderTable(rows) {
  resultTableEl.innerHTML = rows.map((row) => `
    <tr>
      <td>${fmt(row.Candidates)}</td>
      <td>${fmt(row.Workers)}</td>
      <td>${fmt(row['T_seq(ms)'])}</td>
      <td>${fmt(row['T_par(ms)'])}</td>
      <td>${fmt(row.Speedup)}</td>
      <td>${row.Correctness}</td>
    </tr>
  `).join('');
}

function renderGrid(grid) {
  if (!grid) {
    gridSvg.innerHTML = '';
    return;
  }

  const nodes = grid.nodes ?? [];
  const edges = grid.edges ?? [];
  const centerX = 400;
  const centerY = 210;
  const radius = 135;

  const positions = nodes.map((node, index) => {
    const angle = (index / Math.max(1, nodes.length)) * Math.PI * 2 - Math.PI / 2;
    return {
      ...node,
      x: centerX + Math.cos(angle) * radius,
      y: centerY + Math.sin(angle) * radius,
    };
  });

  const lines = edges.map((edge) => {
    const from = positions.find((node) => node.id === edge.from);
    const to = positions.find((node) => node.id === edge.to);
    if (!from || !to) return '';
    return `<line class="edge" x1="${from.x}" y1="${from.y}" x2="${to.x}" y2="${to.y}" />`;
  }).join('');

  const circles = positions.map((node) => `
    <g>
      <circle class="node" cx="${node.x}" cy="${node.y}" r="16"></circle>
      <text class="node-label" x="${node.x}" y="${node.y + 34}">${node.name}</text>
    </g>
  `).join('');

  gridSvg.innerHTML = `${lines}${circles}`;
}

async function refreshData() {
  const payload = await fetchJson('/api/data');
  state.data = payload;
  state.running = payload.state?.running ?? false;
  statusTextEl.textContent = state.running ? `Running ${payload.state?.phase ?? ''}`.trim() : 'Idle';

  const rows = payload.results ?? [];
  renderSummary(payload.state?.parallelSummary ?? payload.state?.lastRunSummary);
  renderCommandStack(payload.state);
  renderComparison(payload.state);
  renderRunDetails(payload.state?.runDetails);
  renderTable(rows);
  renderGrid(payload.grid);

  const logs = await fetchJson('/api/logs');
  logsEl.textContent = logs.map((entry) => `[${entry.time}] [${entry.source}] ${entry.line}`).join('\n');
  logsEl.scrollTop = logsEl.scrollHeight;
}

runForm.addEventListener('submit', async (event) => {
  event.preventDefault();
  const formData = new FormData(runForm);
  const body = Object.fromEntries(formData.entries());

  statusTextEl.textContent = 'Starting';
  await fetchJson('/api/run', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
});

stopBtn.addEventListener('click', async () => {
  await fetchJson('/api/stop', { method: 'POST' });
  await refreshData();
});

setInterval(async () => {
  try {
    await refreshData();
  } catch (error) {
    statusTextEl.textContent = 'Offline';
    logsEl.textContent = String(error.message || error);
  }
}, 2000);

refreshData().catch((error) => {
  logsEl.textContent = String(error.message || error);
});
