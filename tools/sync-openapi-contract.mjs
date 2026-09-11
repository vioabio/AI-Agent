#!/usr/bin/env node

/**
 * Copy the reviewed repository OpenAPI snapshot into the frontend's ignored
 * generation input.  This is deliberately an offline operation; runtime
 * SpringDoc fetching remains available as `openapi:fetch` for staging drift
 * checks, while local code generation can always use this deterministic copy.
 */
import {parseArgs, readJson, writeJson} from './lib/io.mjs';

const args = parseArgs(process.argv.slice(2));
const sourcePath = args.source ?? 'contracts/openapi-v1.1.json';
const targetPath = args.target ?? 'vio-ai-agent-frontend/src/api/openapi.json';
const contract = readJson(sourcePath, 'OpenAPI source contract');
if (!contract || typeof contract !== 'object' || !/^3\.\d+\.\d+$/.test(String(contract.openapi ?? ''))) {
  throw new Error('OpenAPI source contract 必须是 OpenAPI 3.x 文档');
}
if (String(contract?.['x-schema-version'] ?? '') !== 'v1.1') {
  throw new Error('OpenAPI source contract 缺少 x-schema-version=v1.1');
}
writeJson(targetPath, contract, {noClobber: args['no-clobber'] === true});
console.log(JSON.stringify({source: sourcePath.replaceAll('\\', '/'), target: targetPath.replaceAll('\\', '/'), schemaVersion: 'v1.1', pathCount: Object.keys(contract.paths ?? {}).length}, null, 2));
