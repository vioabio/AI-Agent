import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import test from 'node:test';

const root = path.resolve(import.meta.dirname, '..');
const structuredSchema = JSON.parse(fs.readFileSync(
  path.join(root, 'contracts', 'agent-event-v1.1.json'), 'utf8'));
const openapi = JSON.parse(fs.readFileSync(
  path.join(root, 'contracts', 'openapi-v1.1.json'), 'utf8'));

test('structured event schema uses the canonical AgentEvent wire envelope', () => {
  assert.deepEqual(structuredSchema.required, [
    'schemaVersion', 'eventId', 'type', 'sessionId', 'runId', 'traceId', 'timestamp', 'payload'
  ]);
  assert.equal(structuredSchema.properties.eventType, undefined);
  assert.equal(structuredSchema.properties.eventId.type, 'integer');
  assert.equal(structuredSchema.properties.eventId.format, 'int64');
  assert.equal(structuredSchema.properties.eventId.minimum, 1);

  const agentEvent = openapi.components.schemas.AgentEvent;
  for (const field of structuredSchema.required) {
    assert.ok(agentEvent.required.includes(field), `OpenAPI AgentEvent is missing ${field}`);
  }
  assert.equal(agentEvent.properties.eventId.type, structuredSchema.properties.eventId.type);
  assert.equal(agentEvent.properties.eventId.format, structuredSchema.properties.eventId.format);
  assert.equal(agentEvent.properties.eventId.minimum, structuredSchema.properties.eventId.minimum);
  assert.ok(agentEvent.properties.type.enum.includes('context.card'));
  for (const type of structuredSchema.properties.type.enum) {
    assert.ok(agentEvent.properties.type.enum.includes(type), `OpenAPI AgentEvent is missing ${type}`);
  }
});

test('OpenAPI publishes the conversation message endpoint consumed by the frontend', () => {
  const operation = openapi.paths['/chat/conversations/{conversationId}/messages']?.get;
  assert.ok(operation, 'conversation messages endpoint must be present');
  assert.equal(operation.responses['200'].content['application/json'].schema.type, 'array');
  assert.equal(
    operation.responses['200'].content['application/json'].schema.items.$ref,
    '#/components/schemas/ChatMessage'
  );
});
