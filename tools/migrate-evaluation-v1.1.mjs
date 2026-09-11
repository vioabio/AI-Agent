#!/usr/bin/env node

/** Migrate the v1.0 evaluation contract without changing its source files. */
import fs from 'node:fs';
import path from 'node:path';
import {currentCommit, generatedAt, parseArgs, readJson, resolvePath, sha256File, writeJson} from './lib/io.mjs';

const args = parseArgs(process.argv.slice(2));
const source = args.source ?? 'evaluation/manifest.json';
const dictionary = args.dictionary ?? 'evaluation/metric-dictionary.json';
const output = args.output ?? 'evaluation/v1.1/manifest.json';
const dictionaryOutput = args['dictionary-output'] ?? 'evaluation/v1.1/metric-dictionary.json';
const mappingReport = args['mapping-report'] ?? 'artifacts/v1.1/baseline/evaluation-schema-mapping.json';
const sourceManifest = readJson(source, 'v1.0 evaluation manifest');
const sourceDictionary = readJson(dictionary, 'v1.0 metric dictionary');
const migratedManifest = migrateManifest(sourceManifest);
const migratedDictionary = migrateDictionary(sourceDictionary);
writeJson(output, migratedManifest, {noClobber: args['no-clobber'] === true});
writeJson(dictionaryOutput, migratedDictionary, {noClobber: args['no-clobber'] === true});
const mapping = {
  schemaVersion: 'v1.1', producer: 'migrate-evaluation-v1.1', generatedAt: generatedAt(), gitCommit: currentCommit(),
  source: {path: source, sha256: sha256File(resolvePath(source)), schemaVersion: sourceManifest.suiteVersion ?? sourceManifest.schemaVersion},
  outputs: [
    {path: output, sha256: sha256File(resolvePath(output))},
    {path: dictionaryOutput, sha256: sha256File(resolvePath(dictionaryOutput))}
  ],
  mappings: [
    {from: 'suiteVersion', to: 'schemaVersion/suiteVersion', rule: 'set v1.1 while retaining suiteVersion'},
    {from: 'status=scaffold', to: 'status=draft', rule: 'never promote readiness during migration'},
    {from: 'metrics[].minimumSamples', to: 'metrics[].minRequests', rule: 'copy value'},
    {from: 'metrics[].version', to: 'metrics[].thresholdVersion', rule: 'copy value'},
    {from: 'metrics[].formula', to: 'metrics[].denominator/query', rule: 'preserve formula and add explicit fields'}
  ],
  sourceUnmodified: true
};
writeJson(mappingReport, mapping, {noClobber: args['no-clobber'] === true});
console.log(JSON.stringify({passed: true, output, dictionaryOutput, mappingReport}, null, 2));

function migrateManifest(input) {
  const result = structuredClone(input);
  result.schemaVersion = 'v1.1';
  result.suiteVersion = 'v1.1';
  result.sourceSuiteVersion = input.suiteVersion ?? null;
  result.status = 'draft';
  result.generatedAt = generatedAt();
  result.suites = (input.suites ?? []).map((suite) => ({
    ...suite,
    schemaVersion: 'v1.1',
    status: 'draft',
    sourceStatus: suite.status ?? null,
    requiredCases: suite.minimumCases,
    minimumCases: suite.minimumCases
  }));
  result.releaseThresholds = {...(input.releaseThresholds ?? {})};
  return result;
}

function migrateDictionary(input) {
  const result = structuredClone(input);
  result.schemaVersion = 'v1.1';
  result.dictionaryVersion = 'v1.1';
  result.sourceDictionaryVersion = input.dictionaryVersion ?? null;
  result.status = 'draft';
  result.generatedAt = generatedAt();
  result.metrics = (input.metrics ?? []).map((metric) => ({
    ...metric,
    schemaVersion: 'v1.1',
    minRequests: metric.minRequests ?? metric.minimumSamples ?? 0,
    thresholdVersion: metric.thresholdVersion ?? metric.version ?? 'v1.1',
    denominator: metric.denominator ?? metric.formula,
    query: metric.query ?? metric.eligibleRequests,
    severity: metric.severity ?? (metric.type === 'slo' || metric.type === 'cost' ? 'warning' : 'blocker')
  }));
  return result;
}
