-- N0/N1 隔离 fixture。仅可在 disposable PostgreSQL 上执行；不是生产 seed。
BEGIN;
INSERT INTO game_release (id, name, patch_no, language)
VALUES ('pokemon-scarlet-violet', '宝可梦朱/紫（最小 fixture）', 'fixture-pending-review', 'zh-CN')
ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, patch_no = EXCLUDED.patch_no, language = EXCLUDED.language;
INSERT INTO data_release (id, game_release_id, source_url, checksum, status)
VALUES ('pokemon-scarlet-violet-data-v1', 'pokemon-scarlet-violet', 'https://www.pokemon.com/us/pokedex', 'fixture-manifest-sha256', 'FIXTURE_CANDIDATE')
ON CONFLICT (id) DO UPDATE SET checksum = EXCLUDED.checksum, status = EXCLUDED.status;
INSERT INTO pokemon_species (id, national_no, name_zh, slug) VALUES
  (6, 6, '喷火龙', 'charizard'), (25, 25, '皮卡丘', 'pikachu'), (133, 133, '伊布', 'eevee')
ON CONFLICT (id) DO UPDATE SET name_zh = EXCLUDED.name_zh, slug = EXCLUDED.slug;
INSERT INTO pokemon_form (id, species_id, form_key, name_zh) VALUES
  (6001, 6, 'default', '喷火龙'), (25001, 25, 'default', '皮卡丘'), (13301, 133, 'default', '伊布')
ON CONFLICT (id) DO UPDATE SET name_zh = EXCLUDED.name_zh;
INSERT INTO form_fact_version (form_id, data_release_id, type1, type2, hp, attack, defense, sp_attack, sp_defense, speed) VALUES
  (6001, 'pokemon-scarlet-violet-data-v1', '火', '飞行', 78, 84, 78, 109, 85, 100),
  (25001, 'pokemon-scarlet-violet-data-v1', '电', NULL, 35, 55, 40, 50, 50, 90),
  (13301, 'pokemon-scarlet-violet-data-v1', '一般', NULL, 55, 55, 50, 45, 65, 55)
ON CONFLICT (form_id, data_release_id) DO UPDATE SET type1 = EXCLUDED.type1, type2 = EXCLUDED.type2, hp = EXCLUDED.hp, attack = EXCLUDED.attack, defense = EXCLUDED.defense, sp_attack = EXCLUDED.sp_attack, sp_defense = EXCLUDED.sp_defense, speed = EXCLUDED.speed;
INSERT INTO move_fact_version (id, move_key, data_release_id, name_zh, power, type) VALUES
  (1001, 'thunderbolt', 'pokemon-scarlet-violet-data-v1', '十万伏特', 90, '电'),
  (1002, 'flamethrower', 'pokemon-scarlet-violet-data-v1', '喷射火焰', 90, '火'),
  (1003, 'quick-attack', 'pokemon-scarlet-violet-data-v1', '电光一闪', 40, '一般')
ON CONFLICT (id) DO UPDATE SET name_zh = EXCLUDED.name_zh, power = EXCLUDED.power, type = EXCLUDED.type;
INSERT INTO ruleset_version (id, game_release_id, name, enabled) VALUES
  ('story-v1', 'pokemon-scarlet-violet', '剧情规则（fixture）', TRUE),
  ('casual-v1', 'pokemon-scarlet-violet', '休闲规则（fixture）', TRUE),
  ('vgc-ruleset-v1', 'pokemon-scarlet-violet', 'VGC 规则（待审核）', FALSE)
ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, enabled = EXCLUDED.enabled;
INSERT INTO pokemon_availability (form_id, data_release_id, ruleset_id, available) VALUES
  (6001, 'pokemon-scarlet-violet-data-v1', 'casual-v1', TRUE),
  (25001, 'pokemon-scarlet-violet-data-v1', 'casual-v1', TRUE),
  (13301, 'pokemon-scarlet-violet-data-v1', 'casual-v1', TRUE)
ON CONFLICT (form_id, data_release_id, ruleset_id) DO UPDATE SET available = EXCLUDED.available;
COMMIT;
