/**
 * Shared structural contract for v1.1 observation windows.
 *
 * The producer/collector is responsible for obtaining the values from the
 * target environment.  This module deliberately does not trust summary
 * fields such as observationHours: duration is calculated only from the
 * timestamped windows supplied in the JSONL stream.
 */

const SHA256 = /^[a-f0-9]{64}$/i;
const PLACEHOLDER = /^(?:unknown|pending|tbd|null|undefined|n\/a|<[^>]+>)$/i;

/**
 * Inspect a release-scoped observation sequence.
 *
 * The returned `errors` cover structural/cross-row violations.  Callers add
 * release-specific total-request and duration thresholds so GA and
 * DEMO_ONLY can keep their distinct 72/500 and 24/100 policies.
 */
export function analyzeObservationRows(rows, {
  releaseId = null,
  environment = null,
  minPerWindow = 20,
  minWindows = 2,
  requireContinuous = true,
  // collect-observation is normally scheduled every five minutes; a small
  // scheduler/clock boundary gap is therefore tolerated.  Larger gaps are
  // unobserved time and must block the release.
  maxGapMs = 60_000
} = {}) {
  const errors = [];
  const ids = new Set();
  const allChannels = new Set();
  let totalRequests = 0;
  let earliest = null;
  let latest = null;
  let coveredMs = 0;
  let maxGapMsSeen = 0;
  let gapCount = 0;
  let previousStart = null;
  let previousEnd = null;
  let previousId = null;
  let continuous = true;
  let independentSource = true;
  let validRows = 0;

  if (!Array.isArray(rows) || rows.length === 0) {
    return {
      errors: ['observation JSONL 为空'],
      totalRequests: 0,
      durationHours: 0,
      coveredDurationHours: 0,
      windowCount: 0,
      minWindows,
      continuous: false,
      independentSource: false,
      sourceChannels: [],
      maxGapSeconds: null,
      gapCount: 0
    };
  }

  for (const [index, row] of rows.entries()) {
    const line = index + 1;
    if (!row || typeof row !== 'object' || Array.isArray(row)) {
      errors.push(`第 ${line} 行必须是对象`);
      independentSource = false;
      continue;
    }
    validRows += 1;

    if (row.schemaVersion !== 'v1.1') errors.push(`第 ${line} 行 schemaVersion 必须为 v1.1`);
    if (releaseId !== null && String(row.releaseId ?? '') !== String(releaseId)) errors.push(`第 ${line} 行 releaseId 不匹配`);
    if (environment !== null && String(row.environment ?? '') !== String(environment)) errors.push(`第 ${line} 行 environment 不匹配`);
    if (row.passed !== true || String(row.status ?? '').toLowerCase() !== 'passed') {
      errors.push(`第 ${line} 行未完成现场探针（passed/status 必须为 true/passed）`);
    }

    const source = String(row.source ?? '').trim();
    const channels = sourceChannels(row);
    channels.forEach((channel) => allChannels.add(channel));
    if (!source && channels.length === 0) {
      errors.push(`第 ${line} 行缺少独立 source`);
      independentSource = false;
    } else if (channels.length < 2) {
      errors.push(`第 ${line} 行 source 必须包含至少两个独立来源（例如 http + prometheus）`);
      independentSource = false;
    }
    const loweredSource = source.toLowerCase();
    const copiedOrSynthetic = /(?:^|[-_:\s])(?:not[-_ ]?executed|manual|self[-_ ]?reported|synthetic|fixture|summary|declared|copy|copied)(?:$|[-_:\s])/.test(loweredSource);
    const foreignEnvironment = environment === 'prod'
      ? /(?:^|[-_:\s])(?:staging|demo)(?:$|[-_:\s])/.test(loweredSource)
      : environment === 'staging'
        ? /(?:^|[-_:\s])prod(?:$|[-_:\s])/.test(loweredSource)
        : false;
    if (copiedOrSynthetic || row.copiedFrom || foreignEnvironment) {
      errors.push(`第 ${line} 行来源不是允许的 ${environment || '目标环境'} 现场采集`);
      independentSource = false;
    }
    const id = String(row.windowId ?? row.id ?? '').trim();
    if (!id || ids.has(id) || placeholder(id)) errors.push(`第 ${line} 行 windowId 缺失或重复`);
    ids.add(id);

    const count = Number(row.requestCount ?? row.requests ?? row.totalRequests ?? row.denominator ?? row.summary?.requests);
    if (!Number.isInteger(count) || count < minPerWindow) {
      errors.push(`第 ${line} 行 requestCount 低于门槛或不是整数`);
    } else {
      totalRequests += count;
    }

    const start = dateValue(row.windowStart ?? row.startedAt ?? row.start);
    const end = dateValue(row.windowEnd ?? row.endedAt ?? row.end);
    validateSourceEvidence(row, line, errors, start, end, count);
    if (!start || !end || end <= start) {
      errors.push(`第 ${line} 行时间窗口无效`);
      continuous = false;
    } else {
      const durationHours = (end.getTime() - start.getTime()) / 3_600_000;
      coveredMs += end.getTime() - start.getTime();
      // A declared duration is informational only.  If it claims more than
      // the actual interval, reject it instead of allowing a summary field to
      // manufacture a 24/72-hour observation.
      const declared = declaredDuration(row);
      if (declared?.invalid) {
        errors.push(`第 ${line} 行 observationHours/durationHours 必须是非负数字`);
      } else if (declared && declared.value > durationHours + 1e-9) {
        errors.push(`第 ${line} 行自报 observationHours 超过实际窗口跨度；必须使用可验证时间戳`);
      }
      if (previousStart && start < previousStart) {
        errors.push(`第 ${line} 行窗口未按时间排序`);
        continuous = false;
      }
      if (previousEnd) {
        if (start < previousEnd) {
          errors.push(`第 ${line} 行窗口重叠或未按时间排序`);
          continuous = false;
        } else if (requireContinuous && start.getTime() !== previousEnd.getTime()) {
          const gap = start.getTime() - previousEnd.getTime();
          gapCount += 1;
          maxGapMsSeen = Math.max(maxGapMsSeen, gap);
          if (gap > maxGapMs) {
            errors.push(`第 ${line} 行窗口序列不连续（时间间隙 ${formatGap(gap)} 超过允许容差 ${formatGap(maxGapMs)}）`);
            continuous = false;
          }
        }
      }
      previousStart = start;
      previousEnd = end;
      previousId = id;
      if (!earliest || start < earliest) earliest = start;
      if (!latest || end > latest) latest = end;
    }
  }

  if (validRows < minWindows) {
    errors.push(`观察证据至少需要 ${minWindows} 个可验证窗口，不能由单条自报 observationHours/requestCount 代替`);
    continuous = false;
  }

  const durationHours = earliest && latest
    ? (latest.getTime() - earliest.getTime()) / 3_600_000
    : 0;
  const coveredDurationHours = coveredMs / 3_600_000;
  return {
    errors: unique(errors),
    totalRequests,
    durationHours,
    coveredDurationHours,
    windowCount: validRows,
    minWindows,
    continuous: continuous && validRows >= minWindows,
    independentSource: independentSource && allChannels.size >= 2,
    sourceChannels: [...allChannels].sort(),
    firstWindowStart: earliest?.toISOString() ?? null,
    lastWindowEnd: latest?.toISOString() ?? null,
    maxGapSeconds: gapCount > 0 ? Number((maxGapMsSeen / 1000).toFixed(3)) : 0,
    gapCount,
    previousWindowId: previousId
  };
}

/** Return normalized independent telemetry channel names for one row. */
export function sourceChannels(row) {
  const values = [];
  const push = (value) => {
    if (value === undefined || value === null) return;
    if (Array.isArray(value)) return value.forEach(push);
    if (typeof value === 'object') {
      const direct = value.kind ?? value.type ?? value.name ?? value.source ?? value.channel;
      if (direct !== undefined) push(direct);
      else Object.entries(value).forEach(([key, item]) => {
        // Map-shaped sourceEvidence commonly uses {http: {...},
        // prometheus: {...}}.  Include the key as a channel and inspect the
        // nested descriptor for an optional kind/type override.
        push(key);
        if (item && typeof item === 'object') push(item.kind ?? item.type ?? item.name ?? item.source ?? item.channel);
      });
      return;
    }
    const text = String(value).trim().toLowerCase();
    if (!text) return;
    // Split both a canonical source string (live-http-and-prometheus) and
    // explicit lists (http,prometheus) into independently classified tokens.
    for (const token of text.split(/[^a-z0-9]+/i).filter(Boolean)) {
      if (/(?:^|\b)(?:http|https|readiness|health|web)(?:$|\b)/i.test(token)) values.push('http');
      else if (/(?:prometheus|metric|metrics|prom)/i.test(token)) values.push('prometheus');
      else if (/(?:otel|trace|tracing)/i.test(token)) values.push('trace');
      else if (/(?:log|logs|audit)/i.test(token)) values.push('logs');
      // manual/synthetic/not-executed are intentionally not independent
      // telemetry channels.
    }
  };
  push(row?.sourceChannels);
  push(row?.sources);
  push(row?.sourceEvidence);
  push(row?.source);
  return [...new Set(values)];
}

function validateSourceEvidence(row, line, errors, start, end, rowRequestCount) {
  const evidence = row?.sourceEvidence ?? row?.sourceRefs;
  if (evidence === undefined || evidence === null) return;
  const entries = Array.isArray(evidence)
    ? evidence
    : (typeof evidence === 'object' ? Object.entries(evidence).map(([kind, value]) => ({kind, ...(value && typeof value === 'object' ? value : {value})})) : []);
  if (entries.length === 0) {
    errors.push(`第 ${line} 行 sourceEvidence 不能为空`);
    return;
  }
  const evidenceChannels = new Set();
  for (const entry of entries) {
    if (!entry || typeof entry !== 'object') {
      errors.push(`第 ${line} 行 sourceEvidence 项无效`);
      continue;
    }
    const kind = String(entry.kind ?? entry.type ?? entry.name ?? '').trim();
    if (!kind || placeholder(kind)) errors.push(`第 ${line} 行 sourceEvidence 缺少 kind`);
    sourceChannels({source: kind}).forEach((channel) => evidenceChannels.add(channel));
    const observedAt = dateValue(entry.observedAt);
    if (entry.observedAt === undefined || entry.observedAt === null || !observedAt) {
      errors.push(`第 ${line} 行 sourceEvidence 必须包含有效 observedAt`);
    } else if (start && end) {
      const tolerance = 60_000;
      if (observedAt.getTime() < start.getTime() - tolerance || observedAt.getTime() > end.getTime() + tolerance) {
        errors.push(`第 ${line} 行 sourceEvidence observedAt 超出窗口边界`);
      }
    }
    const digest = entry.sha256 ?? entry.responseSha256 ?? entry.evidenceSha256;
    if (digest !== undefined && !SHA256.test(String(digest).trim())) errors.push(`第 ${line} 行 sourceEvidence sha256 无效`);
    const evidenceCount = entry.requestCount ?? entry.requests ?? entry.count;
    if (evidenceCount !== undefined && evidenceCount !== null) {
      const parsedCount = Number(evidenceCount);
      if (!Number.isInteger(parsedCount) || parsedCount < 0 || !Number.isInteger(rowRequestCount) || parsedCount !== rowRequestCount) {
        errors.push(`第 ${line} 行 sourceEvidence requestCount 与窗口分母不一致`);
      }
    }
  }
  if (evidenceChannels.size < 2) {
    errors.push(`第 ${line} 行 sourceEvidence 必须覆盖至少两个独立 telemetry channel`);
  }
}

function declaredDuration(row) {
  let present = false;
  for (const key of ['observationHours', 'durationHours', 'windowHours']) {
    if (row?.[key] === undefined || row?.[key] === null || row?.[key] === '') continue;
    present = true;
    const value = Number(row[key]);
    if (Number.isFinite(value) && value >= 0) return {value};
    return {invalid: true};
  }
  return present ? {invalid: true} : null;
}

function dateValue(value) {
  if (value === undefined || value === null || value === '' || placeholder(value)) return null;
  const date = new Date(String(value));
  return Number.isNaN(date.getTime()) ? null : date;
}

function placeholder(value) {
  return !value || PLACEHOLDER.test(String(value).trim());
}

function unique(values) {
  return [...new Set(values.map((value) => String(value)))];
}

function formatGap(milliseconds) {
  return `${(milliseconds / 1000).toFixed(3)}s`;
}
