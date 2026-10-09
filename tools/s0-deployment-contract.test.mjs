// S0 / F01 部署契约测试：docker-compose 与「启动契约」必须保持一致。
//
// 为什么需要它：F01 的真实缺陷是「compose 使用 prod，但没有把 OIDC/游标变量传入容器」。
// 这类缺陷不会被任何单元测试发现——后端代码完全正确，错的只是部署清单与配置之间的映射。
// 本测试把 compose 的 backend 环境映射与 StartupContractValidator.PROD_REQUIRED 的
// 必需项清单**交叉校验**，让「新增必需环境变量却忘了加进 compose」直接变成红灯。
//
// 同时锁定 demo profile 契约：compose 必须使用 demo（prod 会在未接入 OIDC 时 fail-closed 拒绝启动），
// 且必须为无默认值的密钥提供显式映射。
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import test from 'node:test';
import {fileURLToPath} from 'node:url';

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const composePath = path.join(repoRoot, 'docker-compose.yml');
const validatorPath = path.join(repoRoot, 'src', 'main', 'java', 'com', 'vio', 'vioaiagent',
  'config', 'StartupContractValidator.java');
const demoProfilePath = path.join(repoRoot, 'src', 'main', 'resources', 'application-demo.yml');
const prodProfilePath = path.join(repoRoot, 'src', 'main', 'resources', 'application-prod.yml');
const ciPath = path.join(repoRoot, '.github', 'workflows', 'ci.yml');

const composeText = fs.readFileSync(composePath, 'utf8');
const validatorText = fs.readFileSync(validatorPath, 'utf8');
const demoText = fs.readFileSync(demoProfilePath, 'utf8');
const prodText = fs.readFileSync(prodProfilePath, 'utf8');
const ciText = fs.readFileSync(ciPath, 'utf8');

/** 截取名为 vio-ai-agent 的 service 块（到下一个同级 service 定义为止）。 */
function serviceBlock(text, name) {
  const start = text.indexOf(`  ${name}:`);
  assert.notEqual(start, -1, `compose 中找不到 service: ${name}`);
  const rest = text.slice(start);
  // 下一个顶格 2 空格的 service 定义即为边界。
  const next = rest.slice(1).search(/\n {2}[a-z0-9-]+:\n/);
  return next === -1 ? rest : rest.slice(0, next + 1);
}

/** 从 StartupContractValidator 的 required.put("NAME", ...) 提取必需项名称。 */
function requiredNames() {
  const names = [...validatorText.matchAll(/required\.put\("([A-Z0-9_]+)"/g)].map((m) => m[1]);
  assert.ok(names.length >= 5, `未能从 StartupContractValidator 解析必需项，解析到 ${names.length} 个`);
  return names;
}

test('compose backend 环境映射覆盖启动契约的全部必需项', () => {
  const backend = serviceBlock(composeText, 'vio-ai-agent');
  const missing = requiredNames().filter((name) => !backend.includes(`${name}:`));
  assert.deepEqual(missing, [],
    `以下必需环境变量在 StartupContractValidator 中被要求，但 compose 没有传入容器：\n  ${missing.join('\n  ')}`);
});

test('compose 使用 demo profile 而不是 prod（prod 未接入 OIDC 时会 fail-closed 拒绝启动）', () => {
  const backend = serviceBlock(composeText, 'vio-ai-agent');
  assert.match(backend, /SPRING_PROFILES_ACTIVE:\s*\$\{SPRING_PROFILES_ACTIVE:-demo\}/,
    'compose 必须以 demo 为默认 profile：demo 是 prod 的受限覆盖层，会自动叠加 prod 并放开 OIDC 身份模式');
  // 不应出现写死的 prod，避免有人改回去导致启动阻断回归。
  assert.doesNotMatch(backend, /SPRING_PROFILES_ACTIVE:\s*prod\s*$/m,
    'compose 不得写死 prod profile');
});

test('OIDC 与游标密钥在 compose 中显式映射（F01 的两个缺口）', () => {
  const backend = serviceBlock(composeText, 'vio-ai-agent');

  // 游标签名密钥：prod/demo 都不再提供默认值，缺失必须由 compose 显式注入。
  assert.match(backend, /VIO_RESOURCE_CURSOR_SECRET:\s*\$\{VIO_RESOURCE_CURSOR_SECRET:\?/,
    'VIO_RESOURCE_CURSOR_SECRET 必须以 :? 形式强制注入，不能有本地兜底默认值');

  // OIDC 契约变量必须全部显式映射，否则「接入 OIDC」时容器里永远拿不到这些值。
  for (const key of ['VIO_OIDC_ENABLED', 'VIO_OIDC_REQUIRED', 'VIO_OIDC_ISSUER_URI',
    'VIO_OIDC_JWKS_URI', 'VIO_OIDC_AUTHORIZATION_URI', 'VIO_OIDC_TOKEN_URI',
    'VIO_OIDC_AUDIENCE', 'VIO_OIDC_CLIENT_ID', 'VIO_OIDC_REDIRECT_URIS']) {
    assert.ok(backend.includes(`${key}:`), `compose 缺少 OIDC 变量映射: ${key}`);
  }

  // 事件通道与配额后端也必须有显式映射，避免「开了开关却没有实现」的误判。
  for (const key of ['VIO_EVENT_BROKER_ENABLED', 'VIO_GLOBAL_QUOTA_ENABLED',
    'VIO_GLOBAL_QUOTA_BACKEND', 'VIO_RUN_DURABLE_RESUME']) {
    assert.ok(backend.includes(`${key}:`), `compose 缺少运行时开关映射: ${key}`);
  }
});

test('CI compose smoke 提供启动契约要求的全部变量与 demo profile', () => {
  // CI 的 env 块是为 compose smoke 准备的真实输入；缺项会让 smoke 按设计失败。
  const missing = requiredNames().filter((name) => !ciText.includes(`${name}:`));
  assert.deepEqual(missing, [],
    `CI 未提供以下必需环境变量，Compose smoke 会因启动契约失败：\n  ${missing.join('\n  ')}`);
  assert.match(ciText, /SPRING_PROFILES_ACTIVE:\s*demo/,
    'CI 必须显式使用 demo profile，否则 prod 的 OIDC_REQUIRED=true 会阻断启动');
});

test('demo profile 成对放开 OIDC，且不提供游标密钥默认值', () => {
  // enabled=false + required=true 会被 OidcProperties 判定冲突并阻断启动。
  assert.match(demoText, /enabled:\s*\$\{VIO_OIDC_ENABLED:false\}/,
    'demo 的 oidc.enabled 默认必须是 false');
  assert.match(demoText, /required:\s*\$\{VIO_OIDC_REQUIRED:false\}/,
    'demo 的 oidc.required 默认必须同步为 false，否则启动必然失败');
  assert.match(demoText, /secret:\s*\$\{VIO_RESOURCE_CURSOR_SECRET:\}/,
    'demo 不得内置游标签名密钥默认值，缺失应由启动契约给出字段级错误');
});

test('prod profile 保持 fail-closed 默认并让 demo 叠加 prod', () => {
  assert.match(prodText, /enabled:\s*\$\{VIO_OIDC_ENABLED:false\}/,
    'prod 的 OIDC 默认不得为 true，避免未接入 IdP 时静默失败');
  assert.match(prodText, /required:\s*\$\{VIO_OIDC_REQUIRED:true\}/,
    'prod 必须保持 required=true，防止共享 API Key 被当作生产身份契约');
  assert.match(prodText, /group:\s*\n\s+demo:\s*prod,demo/,
    'prod 必须声明 spring.profiles.group.demo=prod,demo，避免 demo 丢掉生产硬约束');
  assert.match(prodText, /secret:\s*\$\{VIO_RESOURCE_CURSOR_SECRET\}/,
    'prod 的游标签名密钥不得有默认值');
});

test('prod profile 文档不得声称缺项会在启动时被校验（占位符解析更早失败）', () => {
  // 保持注释与真实行为一致：无默认值的 ${VAR} 在属性解析阶段就会失败，
  // 因此不能宣称该场景由 StartupContractValidator 报错。
  assert.doesNotMatch(prodText, /缺失时由 StartupContractValidator/,
    'prod.yml 不应声称无默认值占位符由 StartupContractValidator 处理');
});
