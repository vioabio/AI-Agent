#!/usr/bin/env node

/** Static N3 gate for the self-built MCP experiment boundary. */
import fs from 'node:fs';
import path from 'node:path';
import {parseArgs, repoRelative, resolvePath, writeJson} from './lib/io.mjs';

const args = parseArgs(process.argv.slice(2));
const repoRoot = resolvePath(args['repo-root'] ?? '.');
const markerPath = resolvePath(args.marker ?? 'config/mcp-production-boundary.json');
const pomPath = resolvePath(args.pom ?? 'pom.xml');
const sourceRoot = resolvePath(args['source-root'] ?? 'src/main/java/com/vio/vioaiagent/mcp');
const errors = [];
const warnings = [];
const checks = [];
const marker = readJson(markerPath);
const pom = readText(pomPath);
if (!marker) errors.push('MCP production boundary marker 缺失或不是 JSON');
if (!pom) errors.push('pom.xml 缺失');
if (!fs.existsSync(sourceRoot) || !fs.statSync(sourceRoot).isDirectory()) errors.push('自研 MCP source root 缺失');

if (marker) {
  for (const [key, expected] of [['schemaVersion', 'v1.1'], ['productionMode', 'spring-ai-only'], ['customImplementation', 'experimental'], ['experimentalProfile', 'mcp-lab'], ['requiredExecutionPath', 'ToolExecutionGateway']]) {
    const passed = marker[key] === expected;
    checks.push({name: key, passed, expected, actual: marker[key] ?? null});
    if (!passed) errors.push(`marker.${key} 必须为 ${expected}`);
  }
  const excludes = Array.isArray(marker.productionArtifactExcludes) ? marker.productionArtifactExcludes : [];
  if (!excludes.includes('com/vio/vioaiagent/mcp/**')) errors.push('marker 缺少生产 artifact 排除规则');
  if (marker.remoteEnabledByDefault !== false) errors.push('remoteEnabledByDefault 必须为 false');
}

const productionProfile = pom.match(/<id>production<\/id>[\s\S]*?<\/profile>/)?.[0] ?? '';
const hasProductionProfile = productionProfile.includes('com/vio/vioaiagent/mcp/**');
checks.push({name: 'maven-production-exclusion', passed: hasProductionProfile});
if (!hasProductionProfile) errors.push('pom.xml 未发现 production profile 的自研 MCP artifact 排除规则');

if (fs.existsSync(sourceRoot)) {
  const javaFiles = listFiles(sourceRoot, '.java');
  const annotated = [];
  for (const file of javaFiles) {
    const text = fs.readFileSync(file, 'utf8');
    if (/@(?:Component|Service|Configuration|Bean)\b/.test(text)) annotated.push(repoRelative(file));
  }
  checks.push({name: 'custom-spring-annotations', passed: annotated.length === 0, files: annotated});
  if (annotated.length) errors.push(`自研 MCP 不得直接声明 Spring component: ${annotated.join(', ')}`);
}

const appLab = readText(resolvePath('src/main/resources/application-mcp-lab.yml'));
checks.push({name: 'experimental-profile-file', passed: /on-profile:\s*mcp-lab/.test(appLab)});
if (!/on-profile:\s*mcp-lab/.test(appLab)) errors.push('application-mcp-lab.yml 未声明 mcp-lab profile');

const status = errors.length ? 'blocked' : 'environment-pending';
const report = {schemaVersion: 'v1.1', producer: 'validate-mcp-production-boundary', generatedAt: new Date().toISOString(), repoRoot: '.', status, passed: false, errors, warnings, checks, policy: '生产仅装配 Spring AI MCP provider；自研实现只在 mcp-lab 实验 profile，且生产 artifact 排除。', execution: 'static-only-no-transport-no-secret-read', redacted: true};
if (args.report) writeJson(args.report, report, {noClobber: args['no-clobber'] === true});
console.log(JSON.stringify(report, null, 2));
if (errors.length || args['allow-pending'] !== true) process.exitCode = 1;

function readText(file) { try { return fs.existsSync(file) && fs.statSync(file).isFile() ? fs.readFileSync(file, 'utf8') : ''; } catch { return ''; } }
function readJson(file) { try { return JSON.parse(readText(file)); } catch { return null; } }
function listFiles(dir, suffix) {
  const result = [];
  for (const item of fs.readdirSync(dir, {withFileTypes: true})) {
    const full = path.join(dir, item.name);
    if (item.isSymbolicLink()) { errors.push(`拒绝符号链接: ${repoRelative(full)}`); continue; }
    if (item.isDirectory()) result.push(...listFiles(full, suffix));
    else if (item.name.endsWith(suffix)) result.push(full);
  }
  return result;
}
