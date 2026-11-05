// Static production bundle budgets; byte counts are not browser timing metrics.
import assert from "node:assert/strict";
import { createHash, randomUUID } from "node:crypto";
import { mkdir, readFile, writeFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { gzipSync } from "node:zlib";

const frontend = fileURLToPath(new URL("../", import.meta.url));
const dist = path.join(frontend, "dist");
const manifestBytes = await readFile(path.join(dist, ".vite/manifest.json"));
const manifest = JSON.parse(manifestBytes.toString("utf8"));
const hash = (data) => createHash("sha256").update(data).digest("hex");
const budget = { max_chunk_bytes: 500_000, max_login_static_js_bytes: 850_000 };
const chunks = [];
for (const file of new Set(Object.values(manifest).map((item) => item.file))) {
  if (!file.endsWith(".js")) continue;
  const resolved = path.resolve(dist, file);
  assert(path.relative(dist, resolved).startsWith(`assets${path.sep}`));
  const bytes = await readFile(resolved);
  chunks.push({ file, bytes: bytes.length, gzip_bytes: gzipSync(bytes).length, sha256: hash(bytes) });
}
assert(chunks.length > 0, "No JS chunks found");
function closure(key, seen = new Set()) {
  if (seen.has(key)) return seen;
  assert(manifest[key], "Missing manifest import");
  seen.add(key);
  for (const dependency of manifest[key].imports ?? []) closure(dependency, seen);
  return seen;
}
const dynamicKey = (name) => {
  const entry = Object.entries(manifest).find(([, item]) => item.name === name && item.isDynamicEntry);
  assert(entry, `Missing dynamic entry: ${name}`);
  return entry[0];
};
const login = dynamicKey("LoginPage");
const workbench = dynamicKey("WorkbenchPage");
const tasks = dynamicKey("AnalysisTasksPage");
const candidate = dynamicKey("CandidateDetailDrawer");
const report = dynamicKey("ReportPreviewDrawer");
assert(manifest["index.html"]?.isEntry);
for (const key of [login, workbench, tasks]) assert(manifest["index.html"].dynamicImports.includes(key));
for (const key of [candidate, report]) assert(manifest[workbench].dynamicImports.includes(key));
const loginKeys = closure(login, closure("index.html"));
for (const key of [workbench, tasks, candidate, report]) assert(!loginKeys.has(key), "Feature leaked into login imports");
const loginFiles = new Set([...loginKeys].map((key) => manifest[key].file));
const loginChunks = chunks.filter((chunk) => loginFiles.has(chunk.file));
const loginBytes = loginChunks.reduce((sum, chunk) => sum + chunk.bytes, 0);
const largest = Math.max(...chunks.map((chunk) => chunk.bytes));
const passed = largest <= budget.max_chunk_bytes && loginBytes <= budget.max_login_static_js_bytes;
const result = { schema_version: 1, mode: "static_build_budget_only", status: passed ? "passed" : "failed",
  manifest_sha256: hash(manifestBytes), budgets: budget, largest_chunk_bytes: largest,
  login_static_js_bytes: loginBytes, login_static_gzip_bytes: loginChunks.reduce((sum, chunk) => sum + chunk.gzip_bytes, 0),
  total_js_bytes: chunks.reduce((sum, chunk) => sum + chunk.bytes, 0), login_files: [...loginFiles], chunks,
  browser_latency_measured: false, release_approved: false };
const directory = path.resolve(frontend, "../../artifacts/frontend", `build-${Date.now()}-${randomUUID().slice(0, 8)}`);
await mkdir(directory, { recursive: true });
await writeFile(path.join(directory, "report.json"), JSON.stringify(result, null, 2));
console.log(JSON.stringify({ status: result.status, largest_chunk_bytes: largest, login_static_js_bytes: loginBytes,
  report: path.join(directory, "report.json") }));
if (!passed) process.exitCode = 1;
