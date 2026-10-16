#!/usr/bin/env node

/**
 * Generate the reviewed v1.1 OpenAPI snapshot from the frozen v1.0 baseline.
 *
 * The generator is intentionally dependency-free and local-only: it never
 * starts the application, reads configuration, or contacts a running server.
 * Keeping the baseline as the starting point makes the compatibility rule
 * explicit: v1.1 may add operations/fields, but must not silently remove or
 * weaken the v1.0 contract.
 */
import {parseArgs, readJson, writeJson} from './lib/io.mjs';

const args = parseArgs(process.argv.slice(2));
const baselinePath = args.baseline ?? 'contracts/openapi-v1.0.json';
const outputPath = args.output ?? 'contracts/openapi-v1.1.json';
const baseline = readJson(baselinePath, 'OpenAPI v1.0 baseline');
const candidate = JSON.parse(JSON.stringify(baseline));

candidate.info = {
  ...candidate.info,
  version: '1.1.0-candidate',
  description: 'Versioned Pokémon AI Agent API. The checked-in snapshot is the reviewed local contract; runtime SpringDoc output is compared against it before release.',
  'x-contract-status': 'candidate',
  'x-canonical-source': 'contracts/openapi-v1.1.json'
};
candidate.servers = [{url: '/api', description: 'Spring Boot context path'}];
candidate.tags = [
  {name: 'Authentication', description: 'Anonymous session bootstrap and CSRF-protected browser logout'},
  {name: 'Agent Run', description: 'Durable, replayable agent runs and event streams'},
  {name: 'User Resources', description: 'User-scoped conversations, teams and favorites'},
  {name: 'Pokemon Resources', description: 'Versioned Pokémon facts and teams'},
  {name: 'Pokemon Activities', description: 'Source-backed, freshness-gated activities'},
  {name: 'Knowledge Lifecycle', description: 'Ingestion, review, release and promotion controls'},
  {name: 'Answer and Feedback', description: 'Answer lineage and correction feedback'},
  {name: 'HITL', description: 'Run-bound human approval'},
  {name: 'Memory', description: 'User-scoped consented memory'},
  {name: 'Operations', description: 'Audited operator actions for uncertain external effects'},
  {name: 'Health', description: 'Liveness, readiness and diagnostics'}
];
candidate.components ??= {};
candidate.components.securitySchemes = {
  ...(candidate.components.securitySchemes ?? {}),
  bearerAuth: {type: 'http', scheme: 'bearer', bearerFormat: 'JWT', description: 'Production identity is supplied by the configured trusted gateway/OIDC resource server.'},
  apiKeyAuth: {type: 'apiKey', in: 'header', name: 'X-API-Key', description: 'Service-to-service key; never place a key in a URL.'},
  oidcSession: {type: 'apiKey', in: 'cookie', name: 'VIO_SESSION', description: 'OIDC browser session. Cookie name is configurable; VIO_SESSION is the default. State changes also require the CSRF token and an allowed Origin.'}
};
candidate.components.parameters = {
  ...(candidate.components.parameters ?? {}),
  UserIdHeader: {name: 'X-User-Id', in: 'header', required: false, schema: {type: 'string'}, description: 'Local-only fallback when security is disabled; ignored for an authenticated principal.'},
  RoleHeader: {name: 'X-Role', in: 'header', required: false, schema: {type: 'string'}, description: 'Local/offline role hint; production roles come from the authenticated principal.'},
  IdempotencyKey: {name: 'Idempotency-Key', in: 'header', required: false, schema: {type: 'string', maxLength: 256}},
  TraceIdHeader: {name: 'X-Trace-Id', in: 'header', required: false, schema: {type: 'string', maxLength: 128}},
  RunId: {name: 'runId', in: 'path', required: true, schema: {type: 'string', minLength: 1}},
  ConversationId: {name: 'conversationId', in: 'path', required: true, schema: {type: 'string', minLength: 1}},
  TeamId: {name: 'teamId', in: 'path', required: true, schema: {type: 'integer', format: 'int64', minimum: 1}},
  FavoriteId: {name: 'favoriteId', in: 'path', required: true, schema: {type: 'integer', format: 'int64', minimum: 1}}
};

const schemas = candidate.components.schemas;
Object.assign(schemas, {
  ResultEnvelope: {
    type: 'object',
    required: ['code', 'message', 'timestamp'],
    properties: {
      code: {type: 'integer', format: 'int32'},
      message: {type: 'string'},
      // Result.success() omits null data because Result uses NON_NULL. Keeping
      // this property optional models both typed results and Result<Void>.
      data: {description: 'Optional payload; omitted for Result<Void> responses.'},
      timestamp: {type: 'integer', format: 'int64'}
    }
  },
  ErrorBody: {
    type: 'object',
    required: ['code', 'message'],
    properties: {code: {oneOf: [{type: 'integer'}, {type: 'string'}]}, message: {type: 'string'}}
  },
  SessionStatus: {
    type: 'object',
    required: ['mode', 'authenticated', 'accessDenied', 'principalId', 'tenantId', 'roles',
      'serverTime', 'absoluteExpiresAt', 'loginUrl', 'logoutUrl', 'csrf'],
    description: 'Direct JSON session bootstrap. Nullable fields are always present. API-key and disabled modes report no personal login identity; the API-key credential remains a separate service authentication mechanism.',
    properties: {
      mode: {type: 'string', enum: ['oidc', 'api_key', 'disabled']},
      authenticated: {type: 'boolean', description: 'A validated OIDC principal also passed the server principal policy.'},
      accessDenied: {type: 'boolean', description: 'A validated OIDC identity was denied by the server principal policy.'},
      principalId: {type: 'string', nullable: true},
      tenantId: {type: 'string', nullable: true},
      roles: {type: 'array', items: {type: 'string'}, description: 'Sorted roles derived from the trusted OIDC principal; empty for anonymous, API-key and disabled modes.'},
      serverTime: {type: 'string', format: 'date-time', description: 'Server time used to calculate the remaining lifetime without relying on the browser clock.'},
      absoluteExpiresAt: {type: 'string', format: 'date-time', nullable: true, description: 'Server-enforced absolute deadline for a browser session, or exp for a validated Bearer JWT. Null when no usable OIDC identity is available.'},
      loginUrl: {type: 'string', nullable: true, description: 'Context-relative browser navigation URL for starting OIDC login, such as /api/oauth2/authorization/vio. Null outside OIDC mode.'},
      logoutUrl: {type: 'string', nullable: true, description: 'Context-relative POST URL for local browser logout, such as /api/logout. Null outside OIDC mode.'},
      csrf: {$ref: '#/components/schemas/CsrfStatus'}
    }
  },
  CsrfStatus: {
    type: 'object', required: ['enabled', 'available', 'cookieName', 'headerName'],
    description: 'CSRF token availability and transport names. In OIDC mode the anonymous bootstrap GET prepares a CSRF-TOKEN cookie at /. The token value is read from that cookie by the browser.',
    properties: {
      enabled: {type: 'boolean'},
      available: {type: 'boolean'},
      cookieName: {type: 'string', enum: ['CSRF-TOKEN']},
      headerName: {type: 'string', enum: ['X-CSRF-TOKEN']}
    }
  },
  CreateRunRequest: {
    ...schemas.CreateRunRequest,
    required: ['prompt'],
    additionalProperties: false,
    properties: {
      ...schemas.CreateRunRequest.properties,
      userId: {type: 'string', description: 'Local-only compatibility field; authenticated identity wins.'},
      sessionId: {type: 'string'},
      rulesetVersionId: {type: 'string'},
      dlcId: {type: 'string'},
      dataReleaseId: {type: 'string'},
      knowledgeReleaseId: {type: 'string'},
      indexReleaseId: {type: 'string'},
      contractVersion: {type: 'string', default: 'v1'},
      traceId: {type: 'string'},
      idempotencyKey: {type: 'string', maxLength: 256},
      timeoutSeconds: {type: 'integer', minimum: 1, maximum: 3600, default: 120}
    }
  },
  RunView: {
    ...schemas.RunView,
    required: ['runId', 'status'],
    properties: {
      ...schemas.RunView.properties,
      runId: {type: 'string'}, sessionId: {type: 'string'}, conversationId: {type: 'string'},
      status: {type: 'string', enum: ['CREATED', 'RUNNING', 'WAITING_APPROVAL', 'COMPLETED', 'FAILED', 'CANCELLED', 'TIMED_OUT']},
      eventsUrl: {type: 'string'}, traceId: {type: 'string'}, createdAt: {type: 'string', format: 'date-time'},
      gameReleaseId: {type: 'string'}, dlcId: {type: 'string'}, language: {type: 'string'},
      battleFormatId: {type: 'string'}, battleFormat: {type: 'string', description: 'v1.0 compatibility alias'},
      rulesetVersionId: {type: 'string'}, dataReleaseId: {type: 'string'},
      knowledgeReleaseId: {type: 'string'}, indexReleaseId: {type: 'string'},
      contractVersion: {type: 'string'}, lineageStatus: {type: 'string', enum: ['COMPLETE', 'PARTIAL', 'UNTRACEABLE']},
      answer: {type: 'string'}
    }
  },
  AgentEvent: {
    type: 'object', required: ['schemaVersion', 'eventId', 'type', 'sessionId', 'runId', 'traceId', 'timestamp', 'payload'],
    properties: {
      schemaVersion: {type: 'string'}, eventId: {type: 'integer', format: 'int64', minimum: 1},
      type: {type: 'string', enum: eventTypes()}, sessionId: {type: 'string'}, runId: {type: 'string'},
      traceId: {type: 'string'}, timestamp: {type: 'string', format: 'date-time'}, payload: {type: 'object', additionalProperties: true}
    }
  },
  EventPage: {
    ...schemas.EventPage,
    required: ['events'],
    properties: {
      ...schemas.EventPage.properties,
      // Keep `items` as a plain object for v1.0 diff compatibility; the
      // concrete envelope is documented by AgentEvent above.
      events: {type: 'array', items: {type: 'object', additionalProperties: true}},
      nextEventId: {type: 'integer', format: 'int64', minimum: 0}, terminal: {type: 'boolean'}
    }
  },
  CancelResult: {
    type: 'object', required: ['runId', 'status', 'changed', 'terminalEventId'],
    properties: {runId: {type: 'string'}, status: {type: 'string'}, changed: {type: 'boolean'}, terminalEventId: {type: 'integer', format: 'int64'}}
  },
  CancelRequest: {
    type: 'object', additionalProperties: false,
    properties: {reason: {type: 'string', maxLength: 512, description: '取消原因；空值由服务归一化为 user_requested'}}
  },
  ChatMessage: {
    type: 'object', required: ['id', 'conversationId', 'userId', 'role', 'content'],
    properties: {id: {type: 'string'}, conversationId: {type: 'string'}, runId: {type: 'string'}, userId: {type: 'string'}, role: {type: 'string'}, content: {type: 'string'}, createdAt: {type: 'string', format: 'date-time'}, metadata: {type: 'object', additionalProperties: true}}
  },
  Conversation: {
    type: 'object', required: ['id', 'userId', 'title'],
    properties: {id: {type: 'string'}, userId: {type: 'string'}, title: {type: 'string'}, createdAt: {type: 'string', format: 'date-time'}, updatedAt: {type: 'string', format: 'date-time'}}
  },
  ConversationBody: {type: 'object', properties: {id: {type: 'string'}, title: {type: 'string', maxLength: 256}}},
  TeamMember: {type: 'object', required: ['formId', 'slot'], properties: {id: {type: 'integer', format: 'int64'}, formId: {type: 'integer', format: 'int64', minimum: 1}, slot: {type: 'integer', minimum: 1, maximum: 6}}},
  TeamBody: {
    type: 'object', required: ['name', 'rulesetId', 'dataReleaseId', 'members'],
    properties: {name: {type: 'string', minLength: 1, maxLength: 128}, rulesetId: {type: 'string'}, dataReleaseId: {type: 'string'}, members: {type: 'array', minItems: 1, maxItems: 6, items: {$ref: '#/components/schemas/TeamMemberInput'}}}
  },
  TeamMemberInput: {type: 'object', required: ['formId', 'slot'], properties: {formId: {type: 'integer', format: 'int64', minimum: 1}, slot: {type: 'integer', minimum: 1, maximum: 6}}},
  SavedTeam: {
    ...schemas.SavedTeam,
    required: ['teamId'],
    properties: {
      ...schemas.SavedTeam.properties,
      teamId: {type: 'string', description: 'v1.0 compatibility alias for id'},
      id: {type: 'integer', format: 'int64'}, ownerId: {type: 'string'}, name: {type: 'string'}, rulesetId: {type: 'string'}, dataReleaseId: {type: 'string'}, members: {type: 'array', items: {$ref: '#/components/schemas/TeamMember'}}
    }
  },
  SavedTeamRequest: {type: 'object', required: ['name'], properties: {name: {type: 'string'}, rulesetId: {type: 'string'}, dataReleaseId: {type: 'string'}, members: {type: 'array', items: {$ref: '#/components/schemas/TeamMemberInput'}}}},
  Favorite: {type: 'object', required: ['id', 'ownerId', 'resourceType', 'resourceKey'], properties: {id: {type: 'integer', format: 'int64'}, ownerId: {type: 'string'}, resourceType: {type: 'string'}, resourceKey: {type: 'string'}, label: {type: 'string'}, metadata: {type: 'object', additionalProperties: true}, createdAt: {type: 'string', format: 'date-time'}, updatedAt: {type: 'string', format: 'date-time'}}},
  FavoriteBody: {type: 'object', required: ['resourceType', 'resourceKey'], properties: {resourceType: {type: 'string'}, resourceKey: {type: 'string'}, label: {type: 'string'}, metadata: {type: 'object', additionalProperties: true}}},
  FavoritePatchBody: {type: 'object', minProperties: 1, properties: {label: {type: 'string', maxLength: 256}, metadata: {type: 'object', additionalProperties: true}}},
  PokemonFact: {
    ...schemas.PokemonFact,
    required: ['factId'],
    properties: {
      ...schemas.PokemonFact.properties,
      factId: {type: 'string', description: 'Stable API identifier'}, formId: {type: 'integer', format: 'int64'}, speciesId: {type: 'integer', format: 'int64'}, nationalNo: {type: 'integer'}, slug: {type: 'string'}, speciesNameZh: {type: 'string'}, formKey: {type: 'string'}, formNameZh: {type: 'string'}, dataReleaseId: {type: 'string'}, gameReleaseId: {type: 'string'}, sourceUrl: {type: 'string', format: 'uri'}, checksum: {type: 'string'}, releaseStatus: {type: 'string'}, type1: {type: 'string'}, type2: {type: 'string'}, hp: {type: 'integer'}, attack: {type: 'integer'}, defense: {type: 'integer'}, spAttack: {type: 'integer'}, spDefense: {type: 'integer'}, speed: {type: 'integer'}
    }
  },
  // Deterministic battle calculators.  The request fields deliberately mirror
  // PokemonResourceController's DTOs; every result carries the release and
  // algorithm version so an answer can be reproduced after a ruleset update.
  TypeEffectivenessCalculationRequest: {
    type: 'object',
    required: ['dataReleaseId', 'attackType', 'defendTypes'],
    additionalProperties: false,
    properties: {
      dataReleaseId: {type: 'string', minLength: 1},
      attackType: {type: 'string', minLength: 1},
      defendTypes: {type: 'array', minItems: 1, maxItems: 2, items: {type: 'string', minLength: 1}}
    }
  },
  SpeedCalculationRequest: {
    type: 'object',
    required: ['dataReleaseId', 'base', 'level', 'iv', 'ev', 'natureMultiplier'],
    additionalProperties: false,
    properties: {
      dataReleaseId: {type: 'string', minLength: 1},
      base: {type: 'integer', minimum: 1},
      level: {type: 'integer', minimum: 1, maximum: 100},
      iv: {type: 'integer', minimum: 0, maximum: 31},
      ev: {type: 'integer', minimum: 0, maximum: 252},
      natureMultiplier: {type: 'number', exclusiveMinimum: 0, default: 1}
    }
  },
  DamageCalculationRequest: {
    type: 'object',
    required: ['dataReleaseId', 'level', 'power', 'attack', 'defense', 'modifier'],
    additionalProperties: false,
    properties: {
      dataReleaseId: {type: 'string', minLength: 1},
      level: {type: 'integer', minimum: 1, maximum: 100},
      power: {type: 'integer', minimum: 1},
      attack: {type: 'integer', minimum: 1},
      defense: {type: 'integer', minimum: 1},
      modifier: {type: 'number', minimum: 0, default: 1}
    }
  },
  LegalityMember: {
    type: 'object',
    required: ['formKey', 'moves'],
    additionalProperties: false,
    properties: {
      formKey: {type: 'string', minLength: 1},
      moves: {type: 'array', maxItems: 4, items: {type: 'string', minLength: 1}}
    }
  },
  LegalityCalculationRequest: {
    type: 'object',
    required: ['dataReleaseId', 'rulesetVersionId', 'members'],
    additionalProperties: false,
    properties: {
      dataReleaseId: {type: 'string', minLength: 1},
      rulesetVersionId: {type: 'string', minLength: 1},
      members: {type: 'array', minItems: 1, maxItems: 6, items: {$ref: '#/components/schemas/LegalityMember'}}
    }
  },
  CalculationResult: {
    type: 'object',
    required: ['dataReleaseId', 'calculationVersion', 'value'],
    properties: {
      dataReleaseId: {type: 'string'},
      calculationVersion: {type: 'string'},
      value: {type: 'number'}
    }
  },
  DamageCalculationResult: {
    type: 'object',
    required: ['dataReleaseId', 'calculationVersion', 'minDamage', 'maxDamage', 'modifier'],
    properties: {
      dataReleaseId: {type: 'string'},
      calculationVersion: {type: 'string'},
      minDamage: {type: 'integer', minimum: 0},
      maxDamage: {type: 'integer', minimum: 0},
      modifier: {type: 'number', minimum: 0}
    }
  },
  LegalityCalculationResult: {
    type: 'object',
    required: ['dataReleaseId', 'rulesetVersionId', 'calculationVersion', 'legal', 'reason'],
    properties: {
      dataReleaseId: {type: 'string'},
      rulesetVersionId: {type: 'string'},
      calculationVersion: {type: 'string'},
      legal: {type: 'boolean'},
      reason: {type: 'string'}
    }
  },
  FactQueryResult: {type: 'object', required: ['dataReleaseId', 'gameReleaseId', 'items', 'total'], properties: {dataReleaseId: {type: 'string'}, gameReleaseId: {type: 'string'}, sourceUrl: {type: 'string', format: 'uri'}, checksum: {type: 'string'}, releaseStatus: {type: 'string'}, items: {type: 'array', items: {$ref: '#/components/schemas/PokemonFact'}}, total: {type: 'integer', minimum: 0}}},
  ResourcePageConversation: {type: 'object', required: ['items'], properties: {items: {type: 'array', items: {$ref: '#/components/schemas/Conversation'}}, nextCursor: {type: 'string', nullable: true}}},
  ResourcePageSavedTeam: {type: 'object', required: ['items'], properties: {items: {type: 'array', items: {$ref: '#/components/schemas/SavedTeam'}}, nextCursor: {type: 'string', nullable: true}}},
  ResourcePageFavorite: {type: 'object', required: ['items'], properties: {items: {type: 'array', items: {$ref: '#/components/schemas/Favorite'}}, nextCursor: {type: 'string', nullable: true}}},
  ActivityNotice: {
    type: 'object', required: ['id', 'title', 'sourceUrl', 'gameReleaseId', 'language', 'retrievedAt', 'effectiveFrom', 'freshness', 'status', 'checksum'],
    properties: {id: {type: 'string'}, noticeKey: {type: 'string'}, versionNo: {type: 'integer'}, title: {type: 'string'}, summary: {type: 'string'}, sourceId: {type: 'string'}, sourceUrl: {type: 'string', format: 'uri'}, license: {type: 'string'}, trustLevel: {type: 'string'}, gameReleaseId: {type: 'string'}, dlcId: {type: 'string'}, language: {type: 'string'}, region: {type: 'string'}, eventType: {type: 'string'}, retrievedAt: {type: 'string', format: 'date-time'}, effectiveFrom: {type: 'string', format: 'date-time'}, effectiveTo: {type: 'string', format: 'date-time', nullable: true}, freshness: {type: 'string', enum: ['FRESH', 'STALE', 'EXPIRED', 'NOT_YET_ACTIVE', 'UNKNOWN']}, status: {type: 'string', enum: ['CANDIDATE', 'REVIEWED', 'PUBLISHED', 'ACTIVE']}, checksum: {type: 'string'}, schemaVersion: {type: 'string'}, contentVersion: {type: 'string'}, publishedAt: {type: 'string', format: 'date-time', nullable: true}, createdAt: {type: 'string', format: 'date-time'}, updatedAt: {type: 'string', format: 'date-time'}
    }
  },
  ActivityQueryResult: {type: 'object', required: ['gameReleaseId', 'language', 'items', 'total'], properties: {gameReleaseId: {type: 'string'}, dlcId: {type: 'string'}, language: {type: 'string'}, region: {type: 'string'}, asOf: {type: 'string', format: 'date-time'}, freshnessWindowDays: {type: 'integer'}, items: {type: 'array', items: {$ref: '#/components/schemas/ActivityNotice'}}, total: {type: 'integer'}}},
  AnswerProjection: {
    ...schemas.AnswerProjection,
    required: ['answerId', 'runId', 'traceId', 'lineageStatus'],
    properties: {...schemas.AnswerProjection.properties, answerId: {type: 'string'}, runId: {type: 'string'}, sessionId: {type: 'string'}, conversationId: {type: 'string'}, userId: {type: 'string'}, traceId: {type: 'string'}, status: {type: 'string'}, answer: {type: 'string'}, gameReleaseId: {type: 'string'}, dlcId: {type: 'string'}, battleFormatId: {type: 'string'}, rulesetVersionId: {type: 'string'}, dataReleaseId: {type: 'string'}, knowledgeReleaseId: {type: 'string'}, indexReleaseId: {type: 'string'}, contractVersion: {type: 'string'}, lineageStatus: {type: 'string', enum: ['COMPLETE', 'PARTIAL', 'UNTRACEABLE']}, answerDigest: {type: 'string'}, citations: {type: 'array', items: {type: 'object', additionalProperties: true}, 'x-item-schema': '#/components/schemas/RetrievalCitationCard'}, limitations: {type: 'array', items: {type: 'string'}}, createdAt: {type: 'string', format: 'date-time'}, completedAt: {type: 'string', format: 'date-time'}}
  },
  RetrievalCitationCard: {type: 'object', required: ['sourceUrl', 'evidenceText', 'documentVersion', 'chunkId', 'dataReleaseId', 'knowledgeReleaseId', 'indexReleaseId'], properties: {documentId: {type: 'string'}, documentVersion: {type: 'string'}, sourceTitle: {type: 'string'}, sourceUrl: {type: 'string', format: 'uri'}, chunkId: {type: 'string'}, evidenceText: {type: 'string'}, retrievalScore: {type: 'number'}, retrievedAt: {type: 'string', format: 'date-time'}, dataReleaseId: {type: 'string'}, knowledgeReleaseId: {type: 'string'}, indexReleaseId: {type: 'string'}}},
  FactCard: {type: 'object', required: ['factId', 'name', 'types', 'baseStats', 'gameReleaseId', 'dataReleaseId', 'sourceUrl', 'evidenceText'], properties: {factId: {type: 'string'}, name: {type: 'string'}, types: {type: 'array', items: {type: 'string'}}, baseStats: {type: 'object', additionalProperties: {type: 'integer'}}, abilities: {type: 'array', items: {type: 'string'}}, version: {type: 'string'}, gameReleaseId: {type: 'string'}, dataReleaseId: {type: 'string'}, sourceUrl: {type: 'string', format: 'uri'}, evidenceText: {type: 'string'}}},
  CalculationCard: {type: 'object', required: ['calculationId', 'calculationType', 'result', 'gameReleaseId', 'battleFormatId', 'rulesetVersionId', 'dataReleaseId'], properties: {calculationId: {type: 'string'}, calculationType: {type: 'string'}, result: {}, formulaVersion: {type: 'string'}, gameReleaseId: {type: 'string'}, battleFormatId: {type: 'string'}, rulesetVersionId: {type: 'string'}, dataReleaseId: {type: 'string'}, inputs: {type: 'object', additionalProperties: true}}},
  TeamMatrixCard: {type: 'object', required: ['teamId', 'members', 'gameReleaseId', 'battleFormatId', 'rulesetVersionId', 'dataReleaseId'], properties: {teamId: {type: 'string'}, members: {type: 'array', items: {$ref: '#/components/schemas/TeamMember'}}, gameReleaseId: {type: 'string'}, battleFormatId: {type: 'string'}, rulesetVersionId: {type: 'string'}, dataReleaseId: {type: 'string'}, legality: {type: 'string'}}},
  ToolTimeline: {type: 'object', required: ['toolName', 'status'], properties: {toolName: {type: 'string'}, status: {type: 'string', enum: ['running', 'completed', 'failed', 'rejected']}, eventId: {type: 'string'}, startedAt: {type: 'string', format: 'date-time'}, finishedAt: {type: 'string', format: 'date-time'}, durationMs: {type: 'integer'}, errorCode: {type: 'string'}}},
  ApprovalCard: {type: 'object', required: ['approvalId', 'runId', 'toolName', 'status'], properties: {approvalId: {type: 'string'}, runId: {type: 'string'}, userId: {type: 'string'}, toolName: {type: 'string'}, dangerLevel: {type: 'string', enum: ['SAFE', 'MEDIUM', 'HIGH']}, status: {type: 'string', enum: ['PENDING', 'APPROVED', 'DENIED', 'EXPIRED']}, expiresAt: {type: 'string', format: 'date-time'}, reason: {type: 'string'}}},
  FeedbackRequest: {
    ...schemas.FeedbackRequest,
    properties: {...schemas.FeedbackRequest.properties, feedbackId: {type: 'string'}, traceId: {type: 'string'}, userId: {type: 'string'}, dataReleaseId: {type: 'string'}, knowledgeReleaseId: {type: 'string'}, indexReleaseId: {type: 'string'}, priority: {type: 'string', enum: ['P0', 'P1', 'P2', 'P3']}, createdAt: {type: 'string', format: 'date-time'}}
  },
  FeedbackView: {
    ...schemas.FeedbackView,
    required: ['feedbackId', 'answerId', 'status'],
    properties: {...schemas.FeedbackView.properties, feedbackId: {type: 'string'}, answerId: {type: 'string'}, traceId: {type: 'string'}, errorType: {type: 'string'}, evidence: {type: 'object', additionalProperties: true}, userId: {type: 'string'}, dataReleaseId: {type: 'string'}, knowledgeReleaseId: {type: 'string'}, indexReleaseId: {type: 'string'}, feedbackType: {type: 'string', enum: ['HELPFUL', 'NOT_HELPFUL', 'CORRECTION']}, priority: {type: 'string', enum: ['P0', 'P1', 'P2', 'P3']}, status: {type: 'string', enum: ['SUBMITTED', 'TRIAGED', 'IN_REVIEW', 'APPROVED', 'REJECTED', 'PUBLISHED', 'VERIFIED', 'CLOSED']}, assignedTo: {type: 'string'}, fixReleaseId: {type: 'string'}, evaluationResult: {type: 'string'}, createdAt: {type: 'string', format: 'date-time'}, updatedAt: {type: 'string', format: 'date-time'}, version: {type: 'integer', format: 'int64'}, history: {type: 'array', items: {$ref: '#/components/schemas/FeedbackTransition'}}}
  },
  FeedbackTransition: {type: 'object', properties: {from: {type: 'string'}, to: {type: 'string'}, actorId: {type: 'string'}, role: {type: 'string'}, reason: {type: 'string'}, traceId: {type: 'string'}, at: {type: 'string', format: 'date-time'}}},
  FeedbackCard: {type: 'object', required: ['answerId', 'feedbackId', 'status'], properties: {answerId: {type: 'string'}, feedbackId: {type: 'string'}, status: {type: 'string'}, errorType: {type: 'string'}, priority: {type: 'string'}}},
  ErrorCard: {type: 'object', required: ['code', 'message'], properties: {code: {type: 'string'}, message: {type: 'string'}, retryable: {type: 'boolean'}, details: {type: 'object', additionalProperties: true}}},
  ContextCard: {type: 'object', required: ['gameReleaseId', 'battleFormatId', 'rulesetVersionId', 'language'], properties: {gameReleaseId: {type: 'string'}, dlcId: {type: 'string'}, battleFormatId: {type: 'string'}, rulesetVersionId: {type: 'string'}, dataReleaseId: {type: 'string'}, knowledgeReleaseId: {type: 'string'}, indexReleaseId: {type: 'string'}, language: {type: 'string'}}},
  IngestSource: {type: 'object', required: ['id', 'sourceUrl', 'license', 'trustLevel'], properties: {id: {type: 'string'}, sourceUrl: {type: 'string', format: 'uri'}, title: {type: 'string'}, license: {type: 'string'}, trustLevel: {type: 'string', enum: ['OFFICIAL', 'PARTNER', 'COMMUNITY', 'UNVERIFIED']}, ownerId: {type: 'string'}}},
  IngestDocument: {type: 'object', required: ['id', 'canonicalKey', 'language'], properties: {id: {type: 'string'}, canonicalKey: {type: 'string'}, language: {type: 'string'}, gameReleaseId: {type: 'string'}, battleFormatId: {type: 'string'}, rulesetVersionId: {type: 'string'}}},
  IngestRequest: {type: 'object', required: ['stagedFile', 'source', 'document', 'versionNo', 'parserVersion', 'schemaVersion', 'contentVersion', 'idempotencyKey'], properties: {stagedFile: {type: 'string'}, source: {$ref: '#/components/schemas/IngestSource'}, document: {$ref: '#/components/schemas/IngestDocument'}, versionNo: {type: 'string'}, parserVersion: {type: 'string'}, schemaVersion: {type: 'string'}, contentVersion: {type: 'string'}, idempotencyKey: {type: 'string'}, chunkSize: {type: 'integer'}, overlap: {type: 'integer'}, targetKnowledgeReleaseId: {type: 'string'}, ingestionContractVersion: {type: 'string'}, maxAttempts: {type: 'integer'}}},
  IngestionBatch: {type: 'object', properties: {id: {type: 'string'}, sourceId: {type: 'string'}, idempotencyKey: {type: 'string'}, status: {type: 'string'}, parserVersion: {type: 'string'}, chunkConfig: {type: 'string'}, embeddingModel: {type: 'string'}, acceptedCount: {type: 'integer'}, rejectedCount: {type: 'integer'}, errorMessage: {type: 'string'}, startedAt: {type: 'string', format: 'date-time'}, completedAt: {type: 'string', format: 'date-time'}}},
  DocumentVersion: {type: 'object', properties: {id: {type: 'string'}, documentId: {type: 'string'}, versionNo: {type: 'string'}, checksum: {type: 'string'}, parserVersion: {type: 'string'}, schemaVersion: {type: 'string'}, contentVersion: {type: 'string'}, reviewStatus: {type: 'string', enum: ['PENDING', 'APPROVED', 'REJECTED', 'QUARANTINED', 'EXPIRED']}, retrievedAt: {type: 'string', format: 'date-time'}, publishedAt: {type: 'string', format: 'date-time'}, sizeBytes: {type: 'integer', format: 'int64'}, metadata: {type: 'object', additionalProperties: true}}},
  KnowledgeRelease: {type: 'object', properties: {id: {type: 'string'}, releaseNo: {type: 'string'}, status: {type: 'string', enum: ['CANDIDATE', 'ACTIVE', 'ARCHIVED', 'ROLLED_BACK']}, parserVersion: {type: 'string'}, embeddingModel: {type: 'string'}, dataReleaseId: {type: 'string'}, sampleSize: {type: 'integer'}, recallAt5: {type: 'number'}, citationAccuracy: {type: 'number'}, groundedness: {type: 'number'}, createdAt: {type: 'string', format: 'date-time'}, publishedAt: {type: 'string', format: 'date-time'}}},
  VectorIndexRelease: {type: 'object', properties: {id: {type: 'string'}, knowledgeReleaseId: {type: 'string'}, vectorStore: {type: 'string'}, embeddingModel: {type: 'string'}, dimension: {type: 'integer'}, status: {type: 'string', enum: ['CANDIDATE', 'BUILDING', 'ACTIVE', 'FAILED', 'ROLLED_BACK']}, indexChecksum: {type: 'string'}, createdAt: {type: 'string', format: 'date-time'}, publishedAt: {type: 'string', format: 'date-time'}}},
  IngestionJobView: {type: 'object', required: ['id', 'sourceId', 'contentChecksum', 'targetKnowledgeReleaseId', 'status'], properties: {id: {type: 'string'}, sourceId: {type: 'string'}, contentChecksum: {type: 'string'}, targetKnowledgeReleaseId: {type: 'string'}, ingestionContractVersion: {type: 'string'}, status: {type: 'string'}, attemptCount: {type: 'integer'}, maxAttempts: {type: 'integer'}, nextAttemptAt: {type: 'string', format: 'date-time'}, leaseOwner: {type: 'string'}, leaseExpiresAt: {type: 'string', format: 'date-time'}, resultBatchId: {type: 'string'}, createdAt: {type: 'string', format: 'date-time'}, updatedAt: {type: 'string', format: 'date-time'}, completedAt: {type: 'string', format: 'date-time'}, created: {type: 'boolean'}}},
  IngestionAttempt: {type: 'object', properties: {id: {type: 'string'}, jobId: {type: 'string'}, attemptNo: {type: 'integer'}, status: {type: 'string'}, errorClass: {type: 'string'}, errorCode: {type: 'string'}, errorMessage: {type: 'string'}, backoffMs: {type: 'integer', format: 'int64'}, jitterMs: {type: 'integer', format: 'int64'}, leaseOwner: {type: 'string'}, startedAt: {type: 'string', format: 'date-time'}, completedAt: {type: 'string', format: 'date-time'}}},
  ReplayRequest: {type: 'object', required: ['reason', 'newIdempotencyKey'], properties: {reason: {type: 'string', maxLength: 2000}, newIdempotencyKey: {type: 'string', maxLength: 256}}},
  ReviewRequest: {type: 'object', required: ['status'], properties: {status: {type: 'string', enum: ['PENDING', 'APPROVED', 'REJECTED', 'QUARANTINED', 'EXPIRED']}, reviewer: {type: 'string'}, reason: {type: 'string'}}},
  ReleaseRequest: {type: 'object', properties: {id: {type: 'string'}, releaseNo: {type: 'string'}, parserVersion: {type: 'string'}, embeddingModel: {type: 'string'}, dataReleaseId: {type: 'string'}}},
  IndexRequest: {type: 'object', properties: {id: {type: 'string'}, knowledgeReleaseId: {type: 'string'}, vectorStore: {type: 'string'}, embeddingModel: {type: 'string'}, dimension: {type: 'integer', minimum: 1}}},
  PromotionRequest: {type: 'object', required: ['knowledgeReleaseId', 'dataReleaseId', 'evaluationRunId', 'reportHash', 'reviewer', 'approvalReason'], properties: {indexReleaseId: {type: 'string'}, knowledgeReleaseId: {type: 'string'}, dataReleaseId: {type: 'string'}, evaluationRunId: {type: 'string'}, reportHash: {type: 'string'}, reviewer: {type: 'string'}, approvalReason: {type: 'string'}, requestedAt: {type: 'string', format: 'date-time'}}},
  PromotionResult: {type: 'object', properties: {indexReleaseId: {type: 'string'}, knowledgeReleaseId: {type: 'string'}, dataReleaseId: {type: 'string'}, evaluationRunId: {type: 'string'}, reportHash: {type: 'string'}, approvalId: {type: 'string'}, status: {type: 'string'}, activatedAt: {type: 'string', format: 'date-time'}}},
  EvaluationReportArtifact: {
    type: 'string', format: 'binary',
    description: 'Exact original UTF-8 metrics.json bytes from run-v1.1-eval, at most 1 MiB. Send the file/Blob as the application/json body without parsing, reformatting, or JSON-stringifying it. The required header hashes these exact bytes. This is not the returned EvaluationReport projection.',
    'x-max-bytes': 1048576,
    'x-preserve-original-bytes': true
  },
  EvaluationReport: {
    type: 'object', readOnly: true,
    description: 'Verified read projection of an immutable original artifact. passed is computed by the server using frozen rules and any separately stored baseline. An archival pending report or an initial budget baseline has passed=false. Source/dataset hashes are declarations, not proof of source authenticity.',
    required: ['evaluationRunId', 'reportHash', 'suiteVersion', 'modelVersion', 'dataReleaseId', 'knowledgeReleaseId', 'indexReleaseId', 'sampleSize', 'passed', 'immutable', 'generatedAt', 'metrics', 'thresholds'],
    properties: {
      evaluationRunId: {type: 'string', pattern: '^[A-Za-z0-9._-]{3,128}$'},
      reportHash: {type: 'string', pattern: '^sha256:[a-f0-9]{64}$'},
      suiteVersion: {type: 'string'}, modelVersion: {type: 'string'},
      dataReleaseId: {type: 'string'}, knowledgeReleaseId: {type: 'string'}, indexReleaseId: {type: 'string'},
      sampleSize: {type: 'integer', format: 'int32', minimum: 1}, passed: {type: 'boolean'}, immutable: {type: 'boolean'},
      generatedAt: {type: 'string', format: 'date-time'},
      metrics: {type: 'object', additionalProperties: {type: 'number', format: 'double'}},
      thresholds: {type: 'object', additionalProperties: {type: 'string'}, description: 'Server-derived success-side expressions such as >=0.98; distinct from offline alert/failure thresholds.'}
    }
  },
  ApprovalDecision: {type: 'object', properties: {allowed: {type: 'boolean'}, waiting: {type: 'boolean'}, approvalId: {type: 'string'}, status: {type: 'string'}, reason: {type: 'string'}}},
  FeedbackTransitionRequest: {type: 'object', required: ['target'], properties: {target: {type: 'string'}, actorId: {type: 'string'}, role: {type: 'string'}, reason: {type: 'string'}, traceId: {type: 'string'}, fixReleaseId: {type: 'string'}, evaluationResult: {type: 'string'}}},
  MemoryRequest: {type: 'object', required: ['type', 'content'], properties: {conversationId: {type: 'string'}, type: {type: 'string'}, content: {type: 'string'}, confidence: {type: 'number', minimum: 0, maximum: 1, default: 0.5}, consentId: {type: 'string'}, ttlSeconds: {type: 'integer', minimum: 0, maximum: 315360000}}},
  MemoryView: {type: 'object', properties: {memoryId: {type: 'string'}, type: {type: 'string'}, content: {type: 'string'}, confidence: {type: 'number'}, consentId: {type: 'string'}, effectiveFrom: {type: 'string', format: 'date-time'}, expiresAt: {type: 'string', format: 'date-time'}, createdAt: {type: 'string', format: 'date-time'}}},
  MemoryExport: {type: 'object', properties: {memoryId: {type: 'string'}, type: {type: 'string'}, contentDigest: {type: 'string'}, keyVersion: {type: 'string'}, consentId: {type: 'string'}, effectiveFrom: {type: 'string', format: 'date-time'}, expiresAt: {type: 'string', format: 'date-time'}}},
  ReadinessDetails: {type: 'object', required: ['schemaVersion', 'status', 'blockingReasons'], properties: {schemaVersion: {type: 'string'}, checkedAt: {type: 'string', format: 'date-time'}, readinessState: {type: 'string'}, lifecycleReady: {type: 'boolean'}, dependenciesHealthy: {type: 'boolean'}, activeIndexReleaseId: {type: 'string'}, activeKnowledgeReleaseId: {type: 'string'}, releasePointerReady: {type: 'boolean'}, releasePointerReason: {type: 'string'}, status: {type: 'string', enum: ['READY', 'BLOCKED']}, blockingReasons: {type: 'array', items: {type: 'string'}}}}
});

// Keep v1.0 operations and add v1.1 fields/operations in place.
const p = candidate.paths;
mergeOperation(p, '/chat/runs', 'post', {
  tags: ['Agent Run'], summary: '创建可恢复 Agent Run（异步）',
  parameters: [refParam('Idempotency-Key'), refParam('TraceIdHeader'), refParam('UserIdHeader')],
  responses: {
    ...p['/chat/runs'].post.responses,
    '202': jsonResponse('Run accepted', '#/components/schemas/RunView'),
    '400': emptyResponse('Invalid request'), '401': emptyResponse('Unauthenticated'),
    '409': refResponse('Idempotency conflict', '#/components/schemas/ErrorBody'),
    '429': runAdmissionResponse('RUN_CAPACITY_EXCEEDED, RUN_PRINCIPAL_LIMIT or RUN_TENANT_LIMIT: admission rejected before creating a new Run or claiming its Idempotency-Key.'),
    '503': runAdmissionResponse('RUN_EXECUTION_UNAVAILABLE: runtime is closing or cannot schedule execution. Shutdown rejection creates no new Run; a scheduling failure after persistence may leave a FAILED Run. Retry with the same Idempotency-Key to read that Run rather than duplicate work.')
  },
  'x-canonical-status': 202,
  'x-compatibility-status': 201
});
mergeOperation(p, '/chat/runs/{runId}', 'get', {tags: ['Agent Run'], parameters: [requiredPath('runId'), refParam('UserIdHeader')], responses: {...p['/chat/runs/{runId}'].get.responses, '401': emptyResponse('Unauthenticated'), '404': emptyResponse('Not found')}});
mergeOperation(p, '/chat/runs/{runId}/events', 'get', {tags: ['Agent Run'], parameters: [requiredPath('runId'), {name: 'afterEventId', in: 'query', required: false, schema: {type: 'integer', format: 'int64', minimum: 0}}, {name: 'Last-Event-ID', in: 'header', required: false, schema: {type: 'string'}}, refParam('UserIdHeader')], responses: {...p['/chat/runs/{runId}/events'].get.responses, '400': refResponse('Invalid cursor', '#/components/schemas/ErrorBody'), '410': emptyResponse('Cursor expired'), '401': emptyResponse('Unauthenticated'), '404': emptyResponse('Not found')}});
mergeOperation(p, '/chat/runs/{runId}/cancel', 'post', {
  tags: ['Agent Run'],
  parameters: [requiredPath('runId'), refParam('UserIdHeader'), refParam('Idempotency-Key')],
  requestBody: {
    required: false,
    content: {
      'application/json': {
        schema: {$ref: '#/components/schemas/CancelRequest'}
      }
    }
  },
  responses: {
    ...p['/chat/runs/{runId}/cancel'].post.responses,
    '400': emptyResponse('Invalid request'),
    '401': emptyResponse('Unauthenticated'),
    '404': emptyResponse('Not found'),
    '409': emptyResponse('State conflict'),
    '200': {
      description: 'Cancelled (v1.0 RunView or v1.1 CancelResult)',
      content: {
        'application/json': {
          schema: {oneOf: [{$ref: '#/components/schemas/RunView'}, {$ref: '#/components/schemas/CancelResult'}]}
        }
      }
    }
  }
});
p['/chat/conversations/{conversationId}/messages'] = {
  get: {
    tags: ['Agent Run'],
    operationId: 'listConversationMessages',
    summary: '查询当前主体的会话消息',
    parameters: [requiredPath('conversationId'), refParam('UserIdHeader')],
    responses: {
      '200': {
        description: 'Conversation messages',
        content: {'application/json': {schema: {type: 'array', items: {$ref: '#/components/schemas/ChatMessage'}}}}
      },
      '401': emptyResponse('Unauthenticated'),
      '404': emptyResponse('Not found')
    }
  }
};
p['/chat/runs/{runId}/events/stream'] = {
  get: {tags: ['Agent Run'], operationId: 'streamRunEvents', summary: '按游标订阅 Run 事件（snapshot SSE）', description: '当前实现发送一批已持久化事件后结束；断线客户端使用 afterEventId/Last-Event-ID 继续回放，不会隐式取消 Run。', parameters: [requiredPath('runId'), {name: 'afterEventId', in: 'query', required: false, schema: {type: 'integer', format: 'int64', minimum: 0}}, {name: 'Last-Event-ID', in: 'header', required: false, schema: {type: 'string'}}, refParam('UserIdHeader')], responses: {'200': {description: 'SSE event stream', content: {'text/event-stream': {schema: {$ref: '#/components/schemas/AgentEvent'}}}}, '400': refResponse('Invalid cursor', '#/components/schemas/ErrorBody'), '410': emptyResponse('Cursor expired'), '401': emptyResponse('Unauthenticated'), '404': emptyResponse('Not found')}}
};

addAuthenticationPaths();
addResourcePaths();
addPokemonPaths();
addCalculationPaths();
addActivityPath();
addFeedbackPaths();
addKnowledgePaths();
addHitlPaths();
addMemoryPaths();
addOperationsPaths();
addHealthPaths();
addLegacyPaths();

// Marker consumed by local tooling/review; it is not a secret or runtime flag.
candidate['x-generated-from'] = baselinePath.replaceAll('\\', '/');
candidate['x-schema-version'] = 'v1.1';
writeJson(outputPath, candidate, {noClobber: args['no-clobber'] === true});
console.log(JSON.stringify({output: outputPath.replaceAll('\\', '/'), schemaVersion: 'v1.1', pathCount: Object.keys(candidate.paths).length, schemaCount: Object.keys(candidate.components.schemas).length}, null, 2));

function eventTypes() {
  return ['run.created', 'run.started', 'context.confirmed', 'context.card', 'route.selected', 'retrieval.started', 'retrieval.finished', 'retrieval.citation.card', 'fact.card', 'calculation.card', 'team.matrix', 'activity.card', 'tool.started', 'tool.finished', 'approval.required', 'approval.resolved', 'answer.delta', 'answer.completed', 'citation', 'limitation.added', 'feedback.card', 'error.card', 'usage', 'message.user', 'message.assistant', 'checkpoint.saved', 'run.completed', 'run.failed', 'run.cancelled', 'run.timed_out'];
}

function refParam(name) {
  // Header spelling and component keys are distinct. Keep legacy callers
  // using Idempotency-Key while resolving the actual IdempotencyKey component.
  return {$ref: `#/components/parameters/${name === 'Idempotency-Key' ? 'IdempotencyKey' : name}`};
}
function refResponse(description, ref) { return {description, content: {'application/json': {schema: {$ref: ref}}}}; }

function runAdmissionResponse(description) {
  return {
    ...refResponse(description, '#/components/schemas/ErrorBody'),
    headers: {
      'Retry-After': {
        description: 'Positive delay in seconds before retrying admission. Reuse the original Idempotency-Key when retrying the same request.',
        schema: {type: 'integer', minimum: 1, maximum: 3600}
      }
    }
  };
}
function jsonResponse(description, ref) { return refResponse(description, ref); }
function emptyResponse(description) { return {description}; }
function resultResponse(description, ref, status = '200') { return {[status]: {description, content: {'application/json': {schema: {$ref: ref}}}}}; }
function resultSchema(ref) { return {type: 'object', required: ['code', 'message', 'data', 'timestamp'], properties: {code: {type: 'integer'}, message: {type: 'string'}, data: {$ref: ref}, timestamp: {type: 'integer', format: 'int64'}}}; }
function resultEnvelopeResponse(description, ref) { return {description, content: {'application/json': {schema: resultSchema(ref)}}}; }
function resultArraySchema(ref) { return {type: 'object', required: ['code', 'message', 'data', 'timestamp'], properties: {code: {type: 'integer'}, message: {type: 'string'}, data: {type: 'array', items: {$ref: ref}}, timestamp: {type: 'integer', format: 'int64'}}}; }
function mergeOperation(paths, pathName, method, patch) {
  paths[pathName] ??= {};
  paths[pathName][method] = {...(paths[pathName][method] ?? {}), ...patch};
}
function operation(method, operationId, summary, parameters = [], responses = {}, extra = {}) {
  return {operationId, summary, parameters, responses, ...extra};
}
function body(ref, required = true) { return {required, content: {'application/json': {schema: {$ref: ref}}}}; }
function add(pathName, method, value) { candidate.paths[pathName] ??= {}; candidate.paths[pathName][method] = value; }

function addAuthenticationPaths() {
  add('/auth/session', 'get', operation('get', 'getAuthSession', '读取当前浏览器会话与认证模式', [], {
    '200': {
      ...jsonResponse('Session bootstrap, including anonymous or policy-denied state', '#/components/schemas/SessionStatus'),
      headers: {
        'Cache-Control': {required: true, description: 'Session state must not be stored in browser or intermediary caches.', schema: {type: 'string', enum: ['no-store']}}
      }
    }
  }, {tags: ['Authentication'], security: [],
    description: 'Anonymous bootstrap endpoint; no API key is required. Returns direct JSON with Cache-Control: no-store. OIDC mode exposes a validated principal only after server policy approval and prepares the CSRF cookie. API-key and disabled modes return authenticated=false, accessDenied=false, null principal/tenant/deadline/login/logout and empty roles.'
  }));
  add('/logout', 'post', operation('post', 'logoutOidcSession', '退出当前浏览器 OIDC 会话', [
    {name: 'X-CSRF-TOKEN', in: 'header', required: true, schema: {type: 'string', minLength: 1}, description: 'Token matching the CSRF-TOKEN cookie prepared by GET /auth/session. Required even for an anonymous logout request.'},
    {name: 'Origin', in: 'header', required: false, schema: {type: 'string'}, description: 'Browsers send an allowed Origin, or an allowed Referer as fallback. Non-browser clients may omit both headers while still supplying valid CSRF cookie/token.'}
  ], {
    '204': emptyResponse('Local browser session invalidated; authentication and session/CSRF cookies cleared; no response body'),
    '403': emptyResponse('CSRF token or browser Origin/Referer rejected'),
    '404': emptyResponse('OIDC logout is unavailable when OIDC mode is disabled')
  }, {tags: ['Authentication'], security: [{}, {oidcSession: []}],
    description: 'Available only in OIDC mode and handled by Spring Security. Send POST with credentials and the CSRF header; no request body. An authenticated session is optional. Browser Origin/Referer must be allowed. Successful local logout returns 204; Bearer JWTs retain their own expiry and issuer revocation rules.'
  }));
}

function addResourcePaths() {
  add('/conversations', 'post', operation('post', 'createConversation', '创建会话', [refParam('UserIdHeader'), refParam('Idempotency-Key')], {...resultResponse('Created', '#/components/schemas/Conversation', '201'), '400': emptyResponse('Invalid request'), '401': emptyResponse('Unauthenticated'), '409': refResponse('Idempotency conflict', '#/components/schemas/ErrorBody')}, {requestBody: body('#/components/schemas/ConversationBody')}));
  add('/conversations', 'get', operation('get', 'listConversations', '分页查询当前主体会话', [pageParam('limit'), pageParam('cursor'), refParam('UserIdHeader')], resultResponse('Conversation page', '#/components/schemas/ResourcePageConversation'), {responses: resultResponse('Conversation page', '#/components/schemas/ResourcePageConversation')}));
  add('/conversations/{conversationId}', 'get', operation('get', 'getConversation', '获取会话', [refParam('ConversationId'), refParam('UserIdHeader')], {'200': resultResponse('Conversation', '#/components/schemas/Conversation')['200'], '401': emptyResponse('Unauthenticated'), '404': emptyResponse('Not found')}));
  add('/conversations/{conversationId}', 'patch', operation('patch', 'updateConversation', '修改会话标题', [refParam('ConversationId'), refParam('UserIdHeader')], {'200': resultResponse('Conversation', '#/components/schemas/Conversation')['200'], '400': emptyResponse('Invalid request'), '404': emptyResponse('Not found')}, {requestBody: body('#/components/schemas/ConversationBody')}));
  // ResourceController.deleteConversation returns HTTP 200 with Result<Void>.
  // The JSON envelope is part of the wire contract; it is not a 204 response.
  add('/conversations/{conversationId}', 'delete', operation('delete', 'deleteConversation', '删除会话', [refParam('ConversationId'), refParam('UserIdHeader')], {'200': refResponse('Deleted', '#/components/schemas/ResultEnvelope'), '401': emptyResponse('Unauthenticated'), '404': emptyResponse('Not found')}));
  add('/teams', 'post', operation('post', 'createTeam', '创建版本化队伍', [refParam('UserIdHeader'), refParam('Idempotency-Key')], {...resultResponse('Created', '#/components/schemas/SavedTeam', '201'), '400': emptyResponse('Invalid request'), '401': emptyResponse('Unauthenticated'), '409': emptyResponse('Version or slot conflict')}, {requestBody: body('#/components/schemas/TeamBody')}));
  add('/teams', 'get', operation('get', 'listTeams', '分页查询当前主体队伍', [pageParam('limit'), pageParam('cursor'), refParam('UserIdHeader')], resultResponse('Team page', '#/components/schemas/ResourcePageSavedTeam'), {responses: resultResponse('Team page', '#/components/schemas/ResourcePageSavedTeam')}));
  add('/teams/{teamId}', 'get', operation('get', 'getTeam', '获取队伍', [refParam('TeamId'), refParam('UserIdHeader')], {'200': resultResponse('Team', '#/components/schemas/SavedTeam')['200'], '401': emptyResponse('Unauthenticated'), '404': emptyResponse('Not found')}));
  add('/teams/{teamId}', 'patch', operation('patch', 'updateTeam', '修改队伍', [refParam('TeamId'), refParam('UserIdHeader')], {'200': resultResponse('Team', '#/components/schemas/SavedTeam')['200'], '400': emptyResponse('Invalid request'), '404': emptyResponse('Not found')}, {requestBody: body('#/components/schemas/TeamBody')}));
  add('/teams/{teamId}', 'delete', operation('delete', 'deleteTeam', '删除队伍', [refParam('TeamId'), refParam('UserIdHeader')], {'200': resultResponse('Deleted', '#/components/schemas/ResultEnvelope')['200'], '401': emptyResponse('Unauthenticated'), '404': emptyResponse('Not found')}));
  add('/favorites', 'post', operation('post', 'createFavorite', '创建收藏', [refParam('UserIdHeader'), refParam('Idempotency-Key')], {...resultResponse('Created', '#/components/schemas/Favorite', '201'), '400': emptyResponse('Invalid request'), '401': emptyResponse('Unauthenticated'), '409': emptyResponse('Idempotency conflict')}, {requestBody: body('#/components/schemas/FavoriteBody')}));
  add('/favorites', 'get', operation('get', 'listFavorites', '分页查询当前主体收藏', [pageParam('limit'), pageParam('cursor'), refParam('UserIdHeader')], {200: {description: 'Favorite page', content: {'application/json': {schema: {$ref: '#/components/schemas/ResourcePageFavorite'}}}}, '401': emptyResponse('Unauthenticated')}));
  add('/favorites/{favoriteId}', 'get', operation('get', 'getFavorite', '获取收藏', [refParam('FavoriteId'), refParam('UserIdHeader')], {'200': resultResponse('Favorite', '#/components/schemas/Favorite')['200'], '401': emptyResponse('Unauthenticated'), '404': emptyResponse('Not found')}));
  add('/favorites/{favoriteId}', 'patch', operation('patch', 'updateFavorite', '修改收藏展示字段', [refParam('FavoriteId'), refParam('UserIdHeader')], {'200': resultResponse('Favorite', '#/components/schemas/Favorite')['200'], '400': emptyResponse('Invalid request'), '404': emptyResponse('Not found')}, {requestBody: body('#/components/schemas/FavoritePatchBody')}));
  add('/favorites/{favoriteId}', 'delete', operation('delete', 'deleteFavorite', '删除收藏', [refParam('FavoriteId'), refParam('UserIdHeader')], {'200': resultResponse('Deleted', '#/components/schemas/ResultEnvelope')['200'], '401': emptyResponse('Unauthenticated'), '404': emptyResponse('Not found')}));
}

function addPokemonPaths() {
  const factEnvelope = {oneOf: [{$ref: '#/components/schemas/PokemonFactResultEnvelope'}, {type: 'array', items: {$ref: '#/components/schemas/PokemonFact'}}]};
  const teamEnvelope = {oneOf: [{$ref: '#/components/schemas/SavedTeamResultEnvelope'}, {type: 'array', items: {$ref: '#/components/schemas/SavedTeam'}}]};
  schemas.PokemonFactResultEnvelope = resultSchema('#/components/schemas/FactQueryResult');
  schemas.SavedTeamResultEnvelope = resultArraySchema('#/components/schemas/SavedTeam');
  add('/pokemon/facts', 'get', operation('get', 'listPokemonFacts', '查询版本化宝可梦事实', [requiredQuery('dataReleaseId'), optionalQuery('slug'), optionalQuery('nationalNo', {type: 'integer', minimum: 1}), pageParam('limit')], {'200': {description: 'Fact query (v1.1 envelope or v1.0 array compatibility)', content: {'application/json': {schema: factEnvelope}}}, '400': emptyResponse('Invalid request'), '404': emptyResponse('Release not found'), '409': emptyResponse('Release not queryable')}));
  add('/pokemon/facts/{slug}', 'get', operation('get', 'getPokemonFact', '按 slug 获取单个版本化事实', [requiredPath('slug'), requiredQuery('dataReleaseId')], {'200': resultResponse('Fact', '#/components/schemas/PokemonFact')['200'], '400': emptyResponse('Invalid request'), '404': emptyResponse('Not found')}));
  add('/pokemon/teams', 'post', operation('post', 'createPokemonTeam', '创建版本化队伍（兼容入口）', [refParam('UserIdHeader'), refParam('Idempotency-Key')], {'201': {description: 'Team (envelope or v1.0 object compatibility)', content: {'application/json': {schema: {oneOf: [resultSchema('#/components/schemas/SavedTeam'), {$ref: '#/components/schemas/SavedTeam'}]}}}}, '400': emptyResponse('Invalid request'), '401': emptyResponse('Unauthenticated'), '409': emptyResponse('Conflict')}, {requestBody: body('#/components/schemas/SavedTeamRequest')}));
  add('/pokemon/teams', 'get', operation('get', 'listPokemonTeams', '查询当前主体队伍（兼容入口）', [pageParam('limit'), pageParam('cursor'), refParam('UserIdHeader')], {'200': {description: 'Teams (envelope or v1.0 array compatibility)', content: {'application/json': {schema: teamEnvelope}}}, '401': emptyResponse('Unauthenticated')}));
  add('/pokemon/teams/{teamId}', 'get', operation('get', 'getPokemonTeam', '查询当前主体队伍', [requiredPath('teamId', {type: 'integer', format: 'int64'}), refParam('UserIdHeader')], {'200': resultResponse('Team', '#/components/schemas/SavedTeam')['200'], '401': emptyResponse('Unauthenticated'), '404': emptyResponse('Not found')}));
  add('/pokemon/teams/{teamId}', 'patch', operation('patch', 'updatePokemonTeam', '修改当前主体队伍', [requiredPath('teamId', {type: 'integer', format: 'int64'}), refParam('UserIdHeader')], {'200': resultResponse('Team', '#/components/schemas/SavedTeam')['200'], '400': emptyResponse('Invalid request'), '404': emptyResponse('Not found')}, {requestBody: body('#/components/schemas/SavedTeamRequest')}));
  add('/pokemon/teams/{teamId}', 'delete', operation('delete', 'deletePokemonTeam', '删除当前主体队伍', [requiredPath('teamId', {type: 'integer', format: 'int64'}), refParam('UserIdHeader')], {'200': resultResponse('Deleted', '#/components/schemas/ResultEnvelope')['200'], '401': emptyResponse('Unauthenticated'), '404': emptyResponse('Not found')}));
}

/**
 * N4 deterministic calculation endpoints.  These are additive v1.1 paths;
 * the v1.0 contract has no calculator operations to preserve or reshape.
 */
function addCalculationPaths() {
  add('/pokemon/calculations/type-effectiveness', 'post', operation(
    'post', 'calculateTypeEffectiveness', '计算属性克制倍率', [],
    {
      '200': resultEnvelopeResponse('Type effectiveness', '#/components/schemas/CalculationResult'),
      '400': emptyResponse('Invalid calculation request'),
      '404': emptyResponse('Data release not found')
    },
    {tags: ['Pokemon Resources'], requestBody: body('#/components/schemas/TypeEffectivenessCalculationRequest')}
  ));
  add('/pokemon/calculations/speed', 'post', operation(
    'post', 'calculateSpeed', '计算速度线', [],
    {
      '200': resultEnvelopeResponse('Speed calculation', '#/components/schemas/CalculationResult'),
      '400': emptyResponse('Invalid calculation request'),
      '404': emptyResponse('Data release not found')
    },
    {tags: ['Pokemon Resources'], requestBody: body('#/components/schemas/SpeedCalculationRequest')}
  ));
  add('/pokemon/calculations/damage', 'post', operation(
    'post', 'calculateDamage', '计算基础伤害区间', [],
    {
      '200': resultEnvelopeResponse('Damage range', '#/components/schemas/DamageCalculationResult'),
      '400': emptyResponse('Invalid calculation request'),
      '404': emptyResponse('Data release not found')
    },
    {tags: ['Pokemon Resources'], requestBody: body('#/components/schemas/DamageCalculationRequest')}
  ));
  add('/pokemon/calculations/legality', 'post', operation(
    'post', 'checkTeamLegality', '校验队伍合法性', [],
    {
      '200': resultEnvelopeResponse('Team legality', '#/components/schemas/LegalityCalculationResult'),
      '400': emptyResponse('Invalid calculation request'),
      '404': emptyResponse('Data release not found')
    },
    {tags: ['Pokemon Resources'], requestBody: body('#/components/schemas/LegalityCalculationRequest')}
  ));
}

function addActivityPath() {
  add('/pokemon/activities', 'get', operation('get', 'listPokemonActivities', '查询当前有效活动资讯', [requiredQuery('gameReleaseId'), optionalQuery('dlcId'), requiredQuery('language'), optionalQuery('region'), pageParam('limit')], {'200': resultResponse('Activity query', '#/components/schemas/ActivityQueryResult')['200'], '400': emptyResponse('Invalid request'), '410': emptyResponse('Unavailable')}));
  add('/pokemon/activities/{noticeId}', 'get', operation('get', 'getPokemonActivity', '按 ID 获取活动资讯', [requiredPath('noticeId'),], {'200': resultResponse('Activity', '#/components/schemas/ActivityNotice')['200'], '404': emptyResponse('Not found'), '410': emptyResponse('Stale or unverified')}));
}

function addFeedbackPaths() {
  mergeOperation(p, '/answers/{answerId}/feedback', 'post', {tags: ['Answer and Feedback'], parameters: [requiredPath('answerId'), refParam('UserIdHeader'), {name: 'X-Answer-Trace-Id', in: 'header', required: false, schema: {type: 'string'}}], requestBody: body('#/components/schemas/FeedbackRequest', true), responses: {...p['/answers/{answerId}/feedback'].post.responses, '400': emptyResponse('Invalid request'), '401': emptyResponse('Unauthenticated'), '404': emptyResponse('Answer not found'), '409': emptyResponse('Untraceable or conflict')}});
  add('/answers/{answerId}/feedback', 'get', operation('get', 'listOwnFeedback', '查询自己的反馈', [requiredPath('answerId'), pageParam('limit'), refParam('UserIdHeader')], {'200': {description: 'Feedback list', content: {'application/json': {schema: {type: 'array', items: {$ref: '#/components/schemas/FeedbackView'}}}}}, '401': emptyResponse('Unauthenticated'), '404': emptyResponse('Answer not found')}));
  add('/admin/corrections', 'get', operation('get', 'listCorrections', '查询纠错工单（运营）', [optionalQuery('status'), pageParam('limit'), refParam('UserIdHeader'), refParam('RoleHeader')], {'200': {description: 'Correction list', content: {'application/json': {schema: {type: 'array', items: {$ref: '#/components/schemas/FeedbackView'}}}}}, '401': emptyResponse('Unauthenticated'), '403': emptyResponse('Forbidden')}));
  add('/admin/corrections/{feedbackId}', 'patch', operation('patch', 'transitionCorrection', '推进纠错工单状态', [requiredPath('feedbackId'), refParam('UserIdHeader'), refParam('RoleHeader'), refParam('TraceIdHeader')], {'200': refResponse('Feedback', '#/components/schemas/FeedbackView'), '400': emptyResponse('Invalid transition'), '401': emptyResponse('Unauthenticated'), '403': emptyResponse('Forbidden'), '404': emptyResponse('Not found'), '409': emptyResponse('Conflict')}, {requestBody: body('#/components/schemas/FeedbackTransitionRequest')}));
}

function addKnowledgePaths() {
  const roles = [refParam('RoleHeader'), refParam('UserIdHeader')];
  add('/knowledge/ingestions', 'post', operation('post', 'ingestKnowledge', '提交显式知识摄取任务', roles, {'202': resultResponse('Accepted', '#/components/schemas/KnowledgeIngestionResult')['200'], '400': emptyResponse('Invalid request'), '401': emptyResponse('Unauthenticated'), '403': emptyResponse('Forbidden')}, {requestBody: body('#/components/schemas/IngestRequest')}));
  schemas.KnowledgeIngestionResult = {type: 'object', properties: {batch: {$ref: '#/components/schemas/IngestionBatch'}, documentVersion: {$ref: '#/components/schemas/DocumentVersion'}, chunks: {type: 'array', items: {type: 'object'}}, warnings: {type: 'array', items: {type: 'string'}}}};
  add('/knowledge/ingestion-jobs', 'post', operation('post', 'submitIngestionJob', '提交持久化知识摄取 Job', roles, {'202': resultResponse('Accepted', '#/components/schemas/IngestionJobView')['200'], '400': emptyResponse('Invalid request'), '401': emptyResponse('Unauthenticated'), '403': emptyResponse('Forbidden'), '409': emptyResponse('Conflict'), '503': emptyResponse('Service unavailable')}, {requestBody: body('#/components/schemas/IngestRequest')}));
  add('/knowledge/ingestion-jobs/{jobId}', 'get', operation('get', 'getIngestionJob', '查询摄取 Job', [requiredPath('jobId'), ...roles], {'200': resultResponse('Job', '#/components/schemas/IngestionJobView')['200'], '401': emptyResponse('Unauthenticated'), '403': emptyResponse('Forbidden'), '404': emptyResponse('Not found'), '503': emptyResponse('Service unavailable')}));
  add('/knowledge/ingestion-jobs/{jobId}/attempts', 'get', operation('get', 'listIngestionAttempts', '查询 Job attempts', [requiredPath('jobId'), ...roles], {'200': {description: 'Attempts', content: {'application/json': {schema: resultArraySchema('#/components/schemas/IngestionAttempt')}}}, '401': emptyResponse('Unauthenticated'), '403': emptyResponse('Forbidden'), '404': emptyResponse('Not found')}));
  add('/knowledge/ingestion-dlq/{dlqId}/replay', 'post', operation('post', 'replayIngestionDlq', '重放摄取 DLQ', [requiredPath('dlqId'), ...roles], {'202': resultResponse('Accepted', '#/components/schemas/IngestionJobView')['200'], '400': emptyResponse('Invalid request'), '401': emptyResponse('Unauthenticated'), '403': emptyResponse('Forbidden'), '404': emptyResponse('Not found'), '409': emptyResponse('Conflict')}, {requestBody: body('#/components/schemas/ReplayRequest')}));
  add('/knowledge/versions/{versionId}/review', 'post', operation('post', 'reviewDocumentVersion', '审核文档版本', [requiredPath('versionId'), ...roles], {'200': resultResponse('Document version', '#/components/schemas/DocumentVersion')['200'], '400': emptyResponse('Invalid request'), '401': emptyResponse('Unauthenticated'), '403': emptyResponse('Forbidden'), '404': emptyResponse('Not found')}, {requestBody: body('#/components/schemas/ReviewRequest')}));
  add('/knowledge/ingestions/{batchId}', 'get', operation('get', 'getIngestionBatch', '查询摄取批次状态', [requiredPath('batchId'), ...roles], {'200': resultResponse('Ingestion batch', '#/components/schemas/IngestionBatch')['200'], '401': emptyResponse('Unauthenticated'), '403': emptyResponse('Forbidden'), '404': emptyResponse('Not found')}));
  add('/knowledge/releases', 'post', operation('post', 'createKnowledgeRelease', '创建候选知识发布', roles, {'201': resultResponse('Created', '#/components/schemas/KnowledgeRelease')['200'], '400': emptyResponse('Invalid request'), '401': emptyResponse('Unauthenticated'), '403': emptyResponse('Forbidden')}, {requestBody: body('#/components/schemas/ReleaseRequest')}));
  add('/knowledge/indexes', 'post', operation('post', 'createIndexRelease', '创建候选向量索引', roles, {'201': resultResponse('Created', '#/components/schemas/VectorIndexRelease')['200'], '400': emptyResponse('Invalid request'), '401': emptyResponse('Unauthenticated'), '403': emptyResponse('Forbidden')}, {requestBody: body('#/components/schemas/IndexRequest')}));
  add('/knowledge/indexes/{indexReleaseId}/publish', 'post', operation('post', 'publishIndexLegacy', '发布候选索引（兼容入口）', [requiredPath('indexReleaseId'), ...roles], {'200': resultResponse('Published', '#/components/schemas/ResultEnvelope')['200'], '400': emptyResponse('Invalid request'), '401': emptyResponse('Unauthenticated'), '403': emptyResponse('Forbidden'), '409': emptyResponse('Promotion required')}));
  add('/knowledge/indexes/{indexReleaseId}/promote', 'post', operation('post', 'promoteIndex', '通过 N1b 质量门禁发布候选索引', [requiredPath('indexReleaseId'), ...roles], {'200': resultResponse('Promotion result', '#/components/schemas/PromotionResult')['200'], '400': emptyResponse('Invalid request'), '401': emptyResponse('Unauthenticated'), '403': emptyResponse('Forbidden'), '409': emptyResponse('Quality gate rejected')}, {requestBody: body('#/components/schemas/PromotionRequest')}));
  const reportSecurity = [{bearerAuth: []}, {oidcSession: []}, {apiKeyAuth: []}];
  add('/knowledge/evaluation-reports', 'post', operation('post', 'importEvaluationReportArtifact', '导入离线评测报告原文', [
    {name: 'X-Evaluation-Report-Hash', in: 'header', required: true, schema: {type: 'string', pattern: '^sha256:[a-f0-9]{64}$'}, description: 'SHA-256 of the exact original request bytes, including whitespace and line endings.'},
    {name: 'X-Evaluation-Source', in: 'header', required: true, schema: {type: 'string', minLength: 1, maxLength: 512}, description: 'Non-secret provenance label; the server records the authenticated importer. The label is not a source signature.'},
    {name: 'X-CSRF-TOKEN', in: 'header', required: false, schema: {type: 'string'}, description: 'Required for POST when OIDC is enabled, including authenticated Bearer requests. Obtain the matching CSRF cookie/token before import.'}
  ], {
    '200': resultEnvelopeResponse('Verified report projection; byte-identical imports replay the existing report', '#/components/schemas/EvaluationReport'),
    '400': refResponse('Missing headers or invalid artifact, digest, frozen rules or baseline', '#/components/schemas/ErrorBody'),
    '401': refResponse('Unauthenticated', '#/components/schemas/ErrorBody'),
    '403': refResponse('Role, Origin or CSRF denied', '#/components/schemas/ErrorBody'),
    '409': refResponse('evaluationRunId already exists with different original bytes', '#/components/schemas/ErrorBody'),
    '413': refResponse('Original artifact exceeds 1 MiB', '#/components/schemas/ErrorBody'),
    '503': refResponse('Report storage or integrity verification unavailable', '#/components/schemas/ErrorBody')
  }, {tags: ['Knowledge Lifecycle'], security: reportSecurity,
    description: 'Global operator domain. Requires trusted OPERATOR or QUALITY role; caller-supplied user/role headers cannot authorize import. Upload the original metrics.json as a file/Blob without JSON reserialization. The server verifies the digest and frozen rules, preserves first-import metadata, and never overwrites an evaluationRunId. A successful import may return passed=false and does not publish an index.',
    requestBody: body('#/components/schemas/EvaluationReportArtifact')
  }));
  add('/knowledge/evaluation-reports/{evaluationRunId}', 'get', operation('get', 'getEvaluationReport', '读取经校验的评测报告投影', [requiredPath('evaluationRunId')], {
    '200': resultEnvelopeResponse('Verified report projection', '#/components/schemas/EvaluationReport'),
    '401': refResponse('Unauthenticated', '#/components/schemas/ErrorBody'),
    '403': refResponse('Role denied', '#/components/schemas/ErrorBody'),
    '404': refResponse('Report not found', '#/components/schemas/ErrorBody'),
    '503': refResponse('Report read or integrity verification failed', '#/components/schemas/ErrorBody')
  }, {tags: ['Knowledge Lifecycle'], security: reportSecurity,
    description: 'Global operator domain. Requires OPERATOR, QUALITY, REVIEWER or PUBLISHER. Every read verifies stored original bytes, hash and any baseline chain; raw artifact bytes are not returned.'
  }));
}

function addHitlPaths() {
  add('/hitl/approvals/{approvalId}', 'post', operation('post', 'decideRunApproval', '处理绑定 Run 的审批请求', [requiredPath('approvalId'), requiredQuery('runId'), requiredQuery('approved', {type: 'boolean'}), optionalQuery('reason')], {'200': resultResponse('Approval decision', '#/components/schemas/ApprovalDecision')['200'], '401': emptyResponse('Unauthenticated'), '404': emptyResponse('Approval not found'), '409': emptyResponse('Already decided'), '410': emptyResponse('Expired')}));
  add('/hitl/danger-level', 'get', operation('get', 'getToolDangerLevel', '查询工具危险等级', [requiredQuery('toolName')], {'200': resultResponse('Danger level', '#/components/schemas/ResultEnvelope')['200'], '400': emptyResponse('Invalid request')}));
  add('/hitl/approve', 'post', operation('post', 'approveLegacyTool', '批准工具调用（兼容入口）', [requiredQuery('toolName'), requiredQuery('sessionId')], {'200': resultResponse('Approval', '#/components/schemas/ResultEnvelope')['200'], '410': emptyResponse('Legacy endpoint disabled')}));
  add('/hitl/decide', 'post', operation('post', 'decideLegacyTool', '处理工具审批（兼容入口）', [requiredQuery('toolName'), requiredQuery('sessionId')], {'200': resultResponse('Approval', '#/components/schemas/ResultEnvelope')['200'], '410': emptyResponse('Legacy endpoint disabled')}));
}

function addMemoryPaths() {
  add('/memory', 'post', operation('post', 'remember', '写入用户记忆', [], {'200': resultResponse('Memory export', '#/components/schemas/MemoryExport')['200'], '400': emptyResponse('Invalid request'), '401': emptyResponse('Unauthenticated'), '503': emptyResponse('Memory unavailable')}, {requestBody: body('#/components/schemas/MemoryRequest')}));
  add('/memory', 'get', operation('get', 'listMemory', '查询用户记忆', [optionalQuery('q'), pageParam('limit')], {'200': {description: 'Memory list', content: {'application/json': {schema: resultArraySchema('#/components/schemas/MemoryView')}}}, '401': emptyResponse('Unauthenticated')}));
  add('/memory/export', 'get', operation('get', 'exportMemory', '导出用户记忆元数据', [pageParam('limit')], {'200': {description: 'Memory export', content: {'application/json': {schema: resultArraySchema('#/components/schemas/MemoryExport')}}}, '401': emptyResponse('Unauthenticated')}));
  add('/memory/{memoryId}', 'delete', operation('delete', 'deleteMemory', '删除用户记忆', [requiredPath('memoryId')], {'200': resultResponse('Deleted', '#/components/schemas/ResultEnvelope')['200'], '400': emptyResponse('Not found'), '401': emptyResponse('Unauthenticated')}));
}

function addOperationsPaths() {
  add('/admin/effects/reconcile', 'post', operation('post', 'reconcileUnknownEffect', '人工核对未知外部副作用', [], {
    '200': resultResponse('Reconciled effect', '#/components/schemas/EffectReconciliationResult')['200'],
    '400': emptyResponse('Invalid request'),
    '403': emptyResponse('Operator role required'),
    '409': emptyResponse('Effect is not UNKNOWN or audit unavailable')
  }, {tags: ['Operations'], security: [{bearerAuth: []}, {oidcSession: []}, {apiKeyAuth: []}],
    description: 'Operator-only manual confirmation. This records an audited UNKNOWN to SUCCEEDED/FAILED transition and never replays the tool.',
    requestBody: body('#/components/schemas/EffectReconciliationRequest')
  }));
  schemas.EffectReconciliationRequest = {type: 'object', additionalProperties: false, required: [
    'runId', 'stepNo', 'toolName', 'toolVersion', 'idempotencyKey', 'parameterHash', 'finalStatus', 'resultDigest'
  ], properties: {
    runId: {type: 'string', minLength: 1}, stepNo: {type: 'integer', minimum: 0},
    toolName: {type: 'string', minLength: 1}, toolVersion: {type: 'string', minLength: 1},
    idempotencyKey: {type: 'string', minLength: 1}, parameterHash: {type: 'string', minLength: 1},
    finalStatus: {type: 'string', enum: ['SUCCEEDED', 'FAILED']}, resultDigest: {type: 'string', minLength: 1},
    resultUri: {type: 'string', nullable: true}
  }};
  schemas.EffectReconciliationResult = {type: 'object', required: ['runId', 'status', 'operatorId'], properties: {
    runId: {type: 'string'}, status: {type: 'string', enum: ['SUCCEEDED', 'FAILED']}, operatorId: {type: 'string'}
  }};
}

function addHealthPaths() {
  mergeOperation(p, '/health', 'get', {tags: ['Health'], responses: {'200': resultResponse('Liveness', '#/components/schemas/ResultEnvelope')['200']}});
  add('/ready/details', 'get', operation('get', 'readinessDetails', '详细就绪检查', [], {'200': resultResponse('Ready details', '#/components/schemas/ReadinessDetails')['200'], '503': resultResponse('Blocked details', '#/components/schemas/ReadinessDetails')['503'] ?? {description: 'Blocked details'}}));
  mergeOperation(p, '/ready', 'get', {tags: ['Health'], responses: {...p['/ready'].get.responses, '503': emptyResponse('Not ready')}});
  add('/metrics', 'get', operation('get', 'metrics', '获取 Agent 指标快照（诊断）', [], {'200': resultResponse('Metrics', '#/components/schemas/ResultEnvelope')['200']}, {deprecated: true, description: '诊断端点；生产时序指标使用 Actuator/Prometheus。'}));
}

function addLegacyPaths() {
  const query = [requiredQuery('message'), optionalQuery('chatId')];
  for (const [suffix, id, summary] of [['sync', 'legacyChatSync', '同步基础对话'], ['rag/sync', 'legacyChatRagSync', '同步 RAG 对话'], ['tools/sync', 'legacyChatToolsSync', '同步工具对话'], ['mcp/sync', 'legacyChatMcpSync', '同步 MCP 对话']]) {
    add(`/ai/game_app/chat/${suffix}`, 'get', operation('get', id, summary, query, {'200': {description: 'Text response', content: {'text/plain': {schema: {type: 'string'}}}}, '410': emptyResponse('Legacy endpoint disabled')}, {deprecated: true, tags: ['Agent Run']}));
  }
  for (const [suffix, id, summary] of [['sse', 'legacyChatSse', 'SSE 基础对话'], ['rag/sse', 'legacyChatRagSse', 'SSE RAG 对话'], ['tools/sse', 'legacyChatToolsSse', 'SSE 工具对话'], ['mcp/sse', 'legacyChatMcpSse', 'SSE MCP 对话']]) {
    add(`/ai/game_app/chat/${suffix}`, 'get', operation('get', id, summary, query, {'200': {description: 'Legacy text stream', content: {'text/event-stream': {schema: {type: 'string'}}}}, '410': emptyResponse('Legacy endpoint disabled')}, {deprecated: true, tags: ['Agent Run']}));
  }
  add('/ai/manus/stop', 'get', operation('get', 'legacyManusStop', '停止旧版 Manus 会话', [requiredQuery('sessionId')], {'200': {description: 'Stop acknowledgement', content: {'text/plain': {schema: {type: 'string'}}}}, '404': emptyResponse('Not found'), '410': emptyResponse('Legacy endpoint disabled')}, {deprecated: true, tags: ['Agent Run']}));
}

function pageParam(name) { return {name, in: 'query', required: false, schema: {type: name === 'limit' ? 'integer' : 'string', ...(name === 'limit' ? {minimum: 1, maximum: 200, default: 50} : {})}}; }
function requiredQuery(name, schema = {type: 'string'}) { return {name, in: 'query', required: true, schema}; }
function optionalQuery(name, schema = {type: 'string'}) { return {name, in: 'query', required: false, schema}; }
function requiredPath(name, schema = {type: 'string', minLength: 1}) { return {name, in: 'path', required: true, schema}; }
