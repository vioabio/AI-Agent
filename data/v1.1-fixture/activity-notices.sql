-- N4 活动资讯脱敏 fixture；仅在 disposable PostgreSQL 上执行，不是生产 seed。
BEGIN;
INSERT INTO activity_notice
    (id, notice_key, version_no, title, summary, source_id, source_url, license,
     trust_level, game_release_id, dlc_id, language, region, event_type,
     retrieved_at, effective_from, effective_to, freshness, status, checksum,
     schema_version, content_version, published_at)
VALUES
 ('sv-raid-charizard-2026-09-v1', 'sv-raid-charizard', 1,
  '喷火龙活动资讯（示例）', '用于验证活动窗口、来源和 freshness 的脱敏示例。',
  'vio-curated-activity-fixture', 'https://example.com/pokemon/activity-fixture',
  'example-review-license', 'CURATED', 'pokemon-scarlet-violet',
  'the-hidden-treasure-of-area-zero', 'zh-CN', 'CN', 'RAID',
  '2026-09-01T00:00:00Z', '2026-09-01T00:00:00Z', '2026-09-30T00:00:00Z',
  'FRESH', 'PUBLISHED', 'fixture-activity-sha256-v1', 'v1.1', 'activity-v1',
  '2026-09-01T01:00:00Z')
ON CONFLICT (id) DO NOTHING;
COMMIT;
