#!/usr/bin/env node

/**
 * Copy the v1.0 evaluation examples into the v1.1 namespace while applying
 * only reviewed, deterministic version aliases.  This producer deliberately
 * never pads a suite: the v1.1 minimum-size gate must remain blocked until a
 * domain reviewer supplies real, licensed cases.
 */
import fs from 'node:fs';
import path from 'node:path';
import {
  currentCommit,
  generatedAt,
  parseArgs,
  repoRelative,
  resolvePath,
  sha256File,
  writeAtomic,
  writeJson
} from './lib/io.mjs';

const args = parseArgs(process.argv.slice(2));
const sourceRoot = args['source-root'] ?? 'evaluation/datasets';
const outputRoot = args['output-root'] ?? 'evaluation/v1.1/datasets';
const mappingOutput = args['mapping-output'] ?? 'evaluation/v1.1/dataset-version-mapping.json';
const reportOutput = args.report;
const suiteFiles = [
  'fact-v1.jsonl',
  'calculation-v1.jsonl',
  'legality-v1.jsonl',
  'rag-v1.jsonl',
  'no-answer-v1.jsonl',
  'agent-tool-v1.jsonl',
  'security-redteam-v1.jsonl'
];

const canonical = {
  gameReleaseId: valueOrDefault(args['game-release-id'], 'VIO_GAME_RELEASE_ID', 'pokemon-scarlet-violet'),
  // A missing DLC context is represented by null.  The registry candidate for
  // the complete DLC is recorded below, but is never silently applied to a
  // case that did not select a chapter.
  dlcId: nullableValue(args['dlc-id'], 'VIO_DLC_ID', null),
  language: valueOrDefault(args.language, 'VIO_LANGUAGE', 'zh-CN'),
  battleFormatId: valueOrDefault(args['battle-format-id'], 'VIO_BATTLE_FORMAT_ID', 'casual-singles'),
  rulesetVersionId: valueOrDefault(args['ruleset-version-id'], 'VIO_RULESET_VERSION_ID', 'casual-v1'),
  dataReleaseId: valueOrDefault(args['data-release-id'], 'VIO_DATA_RELEASE_ID', 'pokemon-scarlet-violet-data-v1')
};

const aliases = {
  gameReleaseId: {
    'sv-v1': canonical.gameReleaseId,
    'sv-1': canonical.gameReleaseId,
    'scarlet-violet': canonical.gameReleaseId
  },
  dlcId: {
    'zero-secret-treasure': 'the-hidden-treasure-of-area-zero',
    'the-hidden-treasure-of-area-zero': 'the-hidden-treasure-of-area-zero'
  },
  language: {
    zh: 'zh-CN',
    'zh-cn': 'zh-CN',
    'zh_cn': 'zh-CN',
    'zh-CN': 'zh-CN'
  },
  battleFormatId: {
    singles: 'casual-singles',
    'casual-singles': 'casual-singles',
    doubles: 'casual-doubles',
    'casual-doubles': 'casual-doubles'
  },
  rulesetVersionId: {
    'rules-v1': canonical.rulesetVersionId,
    'casual-v1': 'casual-v1',
    'story-v1': 'story-v1',
    'vgc-ruleset-v1': 'vgc-ruleset-v1'
  },
  dataReleaseId: {
    'data-v1': canonical.dataReleaseId,
    'pokemon-scarlet-violet-data-v1': canonical.dataReleaseId
  }
};

const errors = [];
const suites = [];
const globalCaseIds = new Set();
const sourceRootPath = checkedDirectory(sourceRoot, 'source-root', false);
const outputRootPath = checkedDirectory(outputRoot, 'output-root', true);

for (const fileName of suiteFiles) {
  const suiteErrorStart = errors.length;
  const sourcePath = path.join(sourceRootPath, fileName);
  const outputPath = path.join(outputRootPath, fileName);
  if (!fs.existsSync(sourcePath) || !fs.statSync(sourcePath).isFile()) {
    errors.push(`${fileName}: source file does not exist`);
    continue;
  }
  const rawLines = fs.readFileSync(sourcePath, 'utf8').split(/\r?\n/).filter((line) => line.trim() !== '');
  const outputLines = [];
  const defaultedFields = new Map();
  const aliasRewrites = new Map();
  const localIds = new Set();
  for (let index = 0; index < rawLines.length; index += 1) {
    const lineNumber = index + 1;
    let item;
    try {
      item = JSON.parse(rawLines[index]);
    } catch (error) {
      errors.push(`${fileName}:${lineNumber}: JSONL parse failed (${error.message})`);
      continue;
    }
    if (!item || typeof item !== 'object' || Array.isArray(item)) {
      errors.push(`${fileName}:${lineNumber}: case must be an object`);
      continue;
    }
    if (!item.caseId || typeof item.caseId !== 'string') {
      errors.push(`${fileName}:${lineNumber}: caseId is required`);
      continue;
    }
    if (localIds.has(item.caseId) || globalCaseIds.has(item.caseId)) {
      errors.push(`${fileName}:${lineNumber}: duplicate caseId ${item.caseId}`);
      continue;
    }
    localIds.add(item.caseId);
    globalCaseIds.add(item.caseId);
    const versions = normalizeVersions(item.versions ?? {}, fileName, lineNumber, defaultedFields, aliasRewrites);
    if (!versions) continue;
    // Keep the existing case contract and assertion untouched.  schemaVersion
    // is added as an import marker; no expected answer or sample is invented.
    const normalized = {...item, schemaVersion: 'v1.1', versions};
    outputLines.push(JSON.stringify(normalized));
  }
  // Never publish a partially transformed suite.  A malformed line leaves the
  // source untouched and the producer exits blocked so a reviewer can fix it.
  if (errors.length === suiteErrorStart) {
    try {
      writeAtomic(outputPath, `${outputLines.join('\n')}${outputLines.length ? '\n' : ''}`, {noClobber: args['no-clobber'] === true});
    } catch (error) {
      errors.push(`${fileName}: cannot write output (${error.message})`);
    }
  }
  suites.push({
    suiteId: fileName.replace(/\.jsonl$/, ''),
    sourcePath: repoRelative(sourcePath),
    outputPath: repoRelative(outputPath),
    sourceCount: rawLines.length,
    outputCount: outputLines.length,
    sourceSha256: sha256File(sourcePath),
    outputSha256: fs.existsSync(outputPath) ? sha256File(outputPath) : null,
    defaultedFields: Object.fromEntries(defaultedFields),
    aliasRewrites: Object.fromEntries(aliasRewrites)
  });
}

const mapping = {
  schemaVersion: 'v1.1',
  producer: 'normalize-evaluation-v1.1-datasets',
  generatedAt: generatedAt(),
  gitCommit: currentCommit(),
  sourceRoot: repoRelative(sourceRootPath),
  outputRoot: repoRelative(outputRootPath),
  status: errors.length === 0 ? 'pending-review' : 'blocked',
  releaseGateStatus: 'blocked',
  minimumGateBlocked: true,
  noSampleExpansion: suites.length === suiteFiles.length
    && suites.every((suite) => suite.sourceCount === suite.outputCount),
  canonicalDefaults: canonical,
  registry: {
    status: 'pending-review',
    supportMatrix: 'artifacts/v1.1/baseline/support-matrix.md',
    candidates: {
      gameReleaseId: 'pokemon-scarlet-violet',
      dlcId: 'the-hidden-treasure-of-area-zero',
      language: 'zh-CN',
      battleFormatIds: ['casual-singles', 'casual-doubles'],
      rulesetVersionIds: ['story-v1', 'casual-v1', 'vgc-ruleset-v1'],
      dataReleaseId: 'pokemon-scarlet-violet-data-v1'
    },
    note: '候选 registry 尚未完成领域负责人评审；未显式选择 DLC 的 case 保留 dlcId=null。'
  },
  aliases,
  suites,
  errors
};

try {
  writeJson(mappingOutput, mapping, {noClobber: args['no-clobber'] === true});
} catch (error) {
  errors.push(`mapping-output: cannot write (${error.message})`);
}

const report = {
  schemaVersion: 'v1.1',
  producer: 'normalize-evaluation-v1.1-datasets',
  generatedAt: mapping.generatedAt,
  output: repoRelative(resolvePath(mappingOutput)),
  passed: errors.length === 0,
  status: errors.length === 0 ? 'pending-review' : 'blocked',
  noSampleExpansion: mapping.noSampleExpansion,
  suiteCount: suites.length,
  errors
};
if (reportOutput) writeJson(reportOutput, report, {noClobber: args['no-clobber'] === true});
console.log(JSON.stringify(report, null, 2));
if (errors.length > 0) process.exitCode = 1;

function normalizeVersions(input, fileName, lineNumber, defaults, rewrites) {
  const fields = [
    ['gameReleaseId', canonical.gameReleaseId],
    ['dlcId', canonical.dlcId],
    ['language', canonical.language],
    ['battleFormatId', canonical.battleFormatId],
    ['rulesetVersionId', canonical.rulesetVersionId],
    ['dataReleaseId', canonical.dataReleaseId]
  ];
  const result = {};
  for (const [field, fallback] of fields) {
    const source = input[field];
    if (source === undefined || source === null || source === '') {
      result[field] = fallback;
      defaults.set(field, (defaults.get(field) ?? 0) + 1);
      continue;
    }
    const text = String(source).trim();
    const mapped = aliases[field]?.[text] ?? aliases[field]?.[text.toLowerCase()];
    if (mapped !== undefined) {
      result[field] = mapped;
      if (mapped !== text) {
        const key = `${field}:${text}->${mapped}`;
        rewrites.set(key, (rewrites.get(key) ?? 0) + 1);
      }
    } else if (field === 'dlcId' && /^null$/i.test(text)) {
      result[field] = null;
    } else if (isAllowedCanonical(field, text)) {
      result[field] = text;
    } else {
      errors.push(`${fileName}:${lineNumber}: unknown ${field} alias ${text}`);
      return null;
    }
  }
  return result;
}

function isAllowedCanonical(field, value) {
  if (field === 'gameReleaseId') return value === canonical.gameReleaseId;
  if (field === 'dlcId') return value === canonical.dlcId || value === 'the-hidden-treasure-of-area-zero';
  if (field === 'language') return value === canonical.language;
  if (field === 'battleFormatId') return ['casual-singles', 'casual-doubles'].includes(value);
  if (field === 'rulesetVersionId') return ['story-v1', 'casual-v1', 'vgc-ruleset-v1'].includes(value);
  if (field === 'dataReleaseId') return value === canonical.dataReleaseId;
  return false;
}

function valueOrDefault(argument, envName, fallback) {
  const value = argument ?? process.env[envName] ?? fallback;
  if (value === null || value === undefined || String(value).trim() === '') return fallback;
  return String(value).trim();
}

function nullableValue(argument, envName, fallback) {
  const value = argument ?? process.env[envName] ?? fallback;
  if (value === null || value === undefined || String(value).trim() === '' || /^null$/i.test(String(value).trim())) return null;
  return String(value).trim();
}

function checkedDirectory(value, label, allowCreate) {
  const resolved = resolvePath(value);
  try {
    repoRelative(resolved);
  } catch (error) {
    errors.push(`${label} path invalid: ${error.message}`);
    return resolved;
  }
  if (!fs.existsSync(resolved)) {
    if (allowCreate) fs.mkdirSync(resolved, {recursive: true});
    else errors.push(`${label} does not exist: ${value}`);
  } else if (!fs.statSync(resolved).isDirectory()) {
    errors.push(`${label} is not a directory: ${value}`);
  }
  return resolved;
}
