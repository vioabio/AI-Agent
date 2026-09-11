#!/usr/bin/env node

/** 生成可审计发布单元，不写入 API Key、密码或 Token。 */
import fs from 'node:fs';
import path from 'node:path';
import {execFileSync} from 'node:child_process';

const args = parseArgs(process.argv.slice(2));
const output = path.resolve(process.cwd(), args.output ?? 'artifacts/v1.0/release/release-manifest.json');
const gitCommit = safeGit(['rev-parse', 'HEAD']);
const generatedAt = new Date().toISOString();
const manifest = {
  schemaVersion: '1',
  releaseId: args.release ?? `local-${generatedAt.replace(/[-:.TZ]/g, '')}`,
  generatedAt,
  gitCommit,
  images: {
    backend: args.backendDigest ?? 'not-built',
    frontend: args.frontendDigest ?? 'not-built'
  },
  modelVersion: process.env.MODEL_VERSION ?? 'set-at-release',
  promptVersion: process.env.PROMPT_VERSION ?? 'set-at-release',
  dataReleaseId: process.env.DATA_RELEASE_ID ?? 'set-at-release',
  knowledgeReleaseId: process.env.KNOWLEDGE_RELEASE_ID ?? 'set-at-release',
  indexReleaseId: process.env.INDEX_RELEASE_ID ?? 'set-at-release',
  evaluationRunId: process.env.EVALUATION_RUN_ID ?? 'set-at-release',
  environment: process.env.RELEASE_ENVIRONMENT ?? 'local',
  rollback: {
    previousReleaseManifest: process.env.PREVIOUS_RELEASE_MANIFEST ?? 'required-before-cutover',
    readinessRequired: true,
    databaseMigrationRollback: 'forward-compatible migration or restore snapshot',
    indexRollback: 'switch active_index_release pointer'
  }
};
fs.mkdirSync(path.dirname(output), {recursive: true});
fs.writeFileSync(output, `${JSON.stringify(manifest, null, 2)}\n`, 'utf8');
console.log(`release manifest written: ${output}`);

function safeGit(argv) {
  try {
    return execFileSync('git', argv, {encoding: 'utf8'}).trim();
  } catch {
    return 'unknown';
  }
}

function parseArgs(argv) {
  const result = {};
  for (let index = 0; index < argv.length; index += 1) {
    const token = argv[index];
    if (!token.startsWith('--')) continue;
    const [key, inline] = token.slice(2).split('=', 2);
    result[key] = inline ?? argv[++index];
  }
  return result;
}
