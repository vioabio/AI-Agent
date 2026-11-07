#!/usr/bin/env node

/** Produce the redacted, reviewable Pokémon v1.1 support matrix. */
import fs from 'node:fs';
import path from 'node:path';
import {
  currentCommit,
  generatedAt,
  parseArgs,
  repoOutputInfo,
  sha256File,
  writeAtomic,
  writeJson
} from './lib/io.mjs';

const args = parseArgs(process.argv.slice(2));
const errors = [];
const pendingReasons = [];
const noClobber = args['no-clobber'] === true;
const allowedOptions = new Set([
  'output', 'hash-output', 'report', 'owner', 'official-patch', 'game-release-id', 'dlc-id',
  'data-release-id', 'language', 'battle-format-id', 'ruleset-version-id', 'allow-pending', 'no-clobber'
]);
for (const key of Object.keys(args).filter((key) => key !== '_positional')) {
  if (!allowedOptions.has(key)) errors.push(`未知参数: --${key}`);
}
if (args._positional.length > 0) errors.push(`不支持的位置参数: ${args._positional.join(' ')}`);

const outputArg = args.output ?? 'artifacts/v1.1/baseline/support-matrix.md';
const hashArg = args['hash-output'];
const reportArg = args.report;
let destinationBlocked = false;
const outputInfo = destination(outputArg, 'support matrix 输出');
const hashInfo = hashArg === undefined ? null : destination(hashArg, 'support matrix hash 输出');
const reportInfo = reportArg === undefined ? null : destination(reportArg, 'support matrix 报告');
for (const [label, info, supplied] of [['matrix', outputInfo, true], ['hash', hashInfo, hashArg !== undefined], ['report', reportInfo, reportArg !== undefined]]) {
  if (supplied && !info) destinationBlocked = true;
  if (info && noClobber && fs.existsSync(info.absolute)) { errors.push(`${label} 已存在（--no-clobber）: ${info.relative}`); destinationBlocked = true; }
}
for (const [leftLabel, left, rightLabel, right] of [
  ['output', outputInfo, 'hash-output', hashInfo], ['output', outputInfo, 'report', reportInfo], ['hash-output', hashInfo, 'report', reportInfo]
]) {
  if (left && right && samePath(left.absolute, right.absolute)) { errors.push(`${leftLabel} 与 ${rightLabel} 不能是同一路径`); destinationBlocked = true; }
}

const owner = scalar(args.owner ?? process.env.VIO_BASELINE_OWNER);
const gameReleaseId = scalar(args['game-release-id'] ?? process.env.VIO_GAME_RELEASE_ID) ?? 'pokemon-scarlet-violet';
const dlcId = scalar(args['dlc-id'] ?? process.env.VIO_DLC_ID) ?? 'the-hidden-treasure-of-area-zero';
const language = scalar(args.language ?? process.env.VIO_LANGUAGE) ?? 'zh-CN';
const dataReleaseId = scalar(args['data-release-id'] ?? process.env.VIO_DATA_RELEASE_ID) ?? 'pokemon-scarlet-violet-data-v1';
const battleFormatId = scalar(args['battle-format-id'] ?? process.env.VIO_BATTLE_FORMAT_ID) ?? 'casual-singles';
const rulesetVersionId = scalar(args['ruleset-version-id'] ?? process.env.VIO_RULESET_VERSION_ID) ?? 'casual-v1';
const officialPatch = scalar(args['official-patch'] ?? process.env.VIO_OFFICIAL_PATCH);
if (!owner) pendingReasons.push('owner 尚未冻结（使用 --owner/VIO_BASELINE_OWNER）');
else if (!/^[a-z0-9][a-z0-9._-]{2,63}$/i.test(owner)) errors.push('owner 必须是稳定标识');
if (!officialPatch || /^(?:pending|tbd|unknown)$/i.test(officialPatch)) pendingReasons.push('未冻结官方补丁号（使用 --official-patch 或 VIO_OFFICIAL_PATCH）');
for (const [name, value] of [['game-release-id', gameReleaseId], ['dlc-id', dlcId], ['data-release-id', dataReleaseId], ['battle-format-id', battleFormatId], ['ruleset-version-id', rulesetVersionId]]) {
  if (!/^[a-z0-9][a-z0-9._-]{2,127}$/i.test(value)) errors.push(`${name} 不是合法 canonical id`);
}
if (!/^[a-z]{2}(?:-[A-Z][A-Z])?$/.test(language)) errors.push('language 必须是 BCP-47 简写（如 zh-CN）');

const matrix = {
  schemaVersion: 'v1.1', producer: 'create-support-matrix', id: 'vio-pokemon-sv-v1',
  game_release_id: gameReleaseId, dlc_id: dlcId, language,
  battle_format_id: battleFormatId, ruleset_version_id: rulesetVersionId,
  supported_battle_format_ids: ['casual-singles', 'casual-doubles'],
  supported_ruleset_version_ids: ['story-v1', 'casual-v1', 'vgc-ruleset-v1'],
  data_release_id: dataReleaseId, knowledge_release_id: null, index_release_id: null,
  official_patch: officialPatch,
  unsupported: ['other-generations', 'real-time-ranked-battling', 'account-or-trade-operations', 'unreviewed-rumors'],
  owner: owner ?? 'unassigned', status: errors.length > 0 ? 'blocked' : (pendingReasons.length > 0 ? 'pending' : 'ready-for-review'),
  pendingReasons,
  generatedAt: generatedAt(), gitCommit: currentCommit(), redacted: true
};
const markdown = [
  '# VIO v1.1 首发支持矩阵', '',
  '> 该文件是基线输入，不代表全量数据已经导入。`status=pending` 时不得进入 GA 门禁。', '',
  '```json', JSON.stringify(matrix, null, 2), '```', '',
  '| 维度 | 冻结值 |', '|---|---|',
  `| 游戏 | ${matrix.game_release_id} |`, `| DLC | ${matrix.dlc_id} |`, `| 语言 | ${matrix.language} |`,
  `| 默认赛制 | ${matrix.battle_format_id} |`, `| 支持赛制 | ${matrix.supported_battle_format_ids.join('、')} |`,
  `| 默认规则集 | ${matrix.ruleset_version_id} |`, `| 支持规则集 | ${matrix.supported_ruleset_version_ids.join('、')} |`,
  `| 官方补丁 | ${matrix.official_patch ?? '待领域负责人确认'} |`, ''
].join('\n');

if (!destinationBlocked && outputInfo) {
  try {
    writeAtomic(outputInfo.absolute, markdown, {noClobber});
    if (hashInfo) writeJson(hashInfo.absolute, {
      schemaVersion: 'v1.1', producer: 'create-support-matrix', generatedAt: matrix.generatedAt,
      gitCommit: matrix.gitCommit, owner: matrix.owner,
      files: [{path: outputInfo.relative, sha256: sha256File(outputInfo.absolute), algorithm: 'SHA-256'}]
    }, {noClobber});
  } catch (error) { errors.push(`producer 输出失败: ${safeError(error)}`); }
}
const pendingAllowed = args['allow-pending'] === true;
const effectiveStatus = errors.length > 0 ? 'blocked' : matrix.status;
const report = {
  schemaVersion: 'v1.1', producer: 'create-support-matrix', generatedAt: matrix.generatedAt, gitCommit: matrix.gitCommit,
  owner: matrix.owner, output: outputInfo?.relative ?? '[invalid-path]', hashOutput: hashInfo?.relative ?? null,
  status: effectiveStatus,
  passed: effectiveStatus === 'ready-for-review', exitCode: errors.length === 0 && (pendingReasons.length === 0 || pendingAllowed) ? 0 : 1, redacted: true, errors, pendingReasons
};
if (reportInfo && !(noClobber && fs.existsSync(reportInfo.absolute))) {
  try { writeJson(reportInfo.absolute, report, {noClobber}); } catch (error) { errors.push(`报告写入失败: ${safeError(error)}`); }
}
console.log(JSON.stringify(report, null, 2));
if (report.exitCode !== 0) process.exitCode = 1;

function destination(value, label) {
  try { return repoOutputInfo(value, label); }
  catch (error) { errors.push(`${label} 路径无效: ${safeError(error)}`); return null; }
}
function scalar(value) {
  if (value === undefined || value === null || value === true || value === false) return null;
  const text = String(value).trim(); return text || null;
}
function samePath(left, right) {
  const a = path.resolve(left); const b = path.resolve(right);
  return process.platform === 'win32' ? a.toLowerCase() === b.toLowerCase() : a === b;
}
function safeError(error) { return String(error?.message ?? error).replace(/[\r\n]/g, ' ').slice(0, 500); }
