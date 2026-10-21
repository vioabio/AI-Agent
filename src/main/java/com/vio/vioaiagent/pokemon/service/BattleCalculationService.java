package com.vio.vioaiagent.pokemon.service;

import com.vio.vioaiagent.pokemon.calculator.SpeedLineCalculator;
import com.vio.vioaiagent.pokemon.calculator.DamageCalculator;
import com.vio.vioaiagent.pokemon.calculator.TeamLegalityChecker;
import com.vio.vioaiagent.pokemon.calculator.TypeEffectivenessCalculator;
import com.vio.vioaiagent.pokemon.domain.RulesetBinding;
import com.vio.vioaiagent.pokemon.domain.VersionedDataRelease;
import com.vio.vioaiagent.pokemon.repository.PokemonFactRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.List;

/** 领域编排层：负责版本参数校验和调用纯 calculator。 */
@Service
public class BattleCalculationService {
    private final TypeEffectivenessCalculator typeCalculator;
    private final SpeedLineCalculator speedCalculator;
    private final DamageCalculator damageCalculator;
    private final TeamLegalityChecker legalityChecker;
    /**
     * 生产路径的版本目录；纯 calculator 单测可以显式使用无仓储构造器。
     * 不能在生产请求中仅凭字符串非空判断 release 有效性。
     */
    private final PokemonFactRepository releaseRepository;

    @Autowired
    public BattleCalculationService(ObjectProvider<PokemonFactRepository> releaseProvider) {
        this(new TypeEffectivenessCalculator(), new SpeedLineCalculator(),
                new DamageCalculator(), new TeamLegalityChecker(),
                releaseProvider == null ? null : releaseProvider.getIfAvailable());
    }

    /** 纯计算/兼容构造器；无 Spring 仓储时只验证非空输入。 */
    public BattleCalculationService() {
        this(new TypeEffectivenessCalculator(), new SpeedLineCalculator(),
                new DamageCalculator(), new TeamLegalityChecker(), null);
    }

    public BattleCalculationService(TypeEffectivenessCalculator typeCalculator, SpeedLineCalculator speedCalculator) {
        this(typeCalculator, speedCalculator, new DamageCalculator(), new TeamLegalityChecker(), null);
    }

    public BattleCalculationService(TypeEffectivenessCalculator typeCalculator,
                                    SpeedLineCalculator speedCalculator,
                                    DamageCalculator damageCalculator,
                                    TeamLegalityChecker legalityChecker) {
        this(typeCalculator, speedCalculator, damageCalculator, legalityChecker, null);
    }

    public BattleCalculationService(TypeEffectivenessCalculator typeCalculator,
                                    SpeedLineCalculator speedCalculator,
                                    DamageCalculator damageCalculator,
                                    TeamLegalityChecker legalityChecker,
                                    PokemonFactRepository releaseRepository) {
        this.typeCalculator = typeCalculator;
        this.speedCalculator = speedCalculator;
        this.damageCalculator = damageCalculator;
        this.legalityChecker = legalityChecker;
        this.releaseRepository = releaseRepository;
    }

    public CalculationResult typeEffectiveness(String dataReleaseId, String attackType, String... defendTypes) {
        requireRelease(dataReleaseId);
        return new CalculationResult(dataReleaseId, TypeEffectivenessCalculator.CALCULATION_VERSION,
                typeCalculator.calculate(attackType, defendTypes));
    }

    public CalculationResult speed(String dataReleaseId, int base, int level, int iv, int ev, double natureMultiplier) {
        requireRelease(dataReleaseId);
        return new CalculationResult(dataReleaseId, SpeedLineCalculator.CALCULATION_VERSION,
                speedCalculator.calculate(base, level, iv, ev, natureMultiplier));
    }

    /**
     * 计算可复现的基础伤害区间。天气、特性、道具和太晶修正必须由调用方
     * 预先折算到 modifier，避免服务层凭模型常识补参数。
     */
    public DamageResult damage(String dataReleaseId, int level, int power, int attack,
                               int defense, double modifier) {
        requireRelease(dataReleaseId);
        DamageCalculator.DamageRange range = damageCalculator.calculate(
                level, power, attack, defense, modifier);
        return new DamageResult(dataReleaseId, DamageCalculator.CALCULATION_VERSION,
                range.min(), range.max(), modifier);
    }

    /**
     * 运行结构化队伍合法性检查；详细规则由冻结的 ruleset 继续扩展，当前
     * 版本至少保证成员数量、重复形态和招式数量边界。
     */
    public LegalityResult legality(String dataReleaseId, String rulesetVersionId,
                                   List<TeamLegalityChecker.Member> members) {
        requireRelease(dataReleaseId);
        if (rulesetVersionId == null || rulesetVersionId.isBlank()) {
            throw new IllegalArgumentException("缺少 ruleset_version_id");
        }
        String normalizedRuleset = rulesetVersionId.trim();
        if (releaseRepository != null) {
            RulesetBinding ruleset = releaseRepository.findRuleset(normalizedRuleset)
                    .orElseThrow(() -> new IllegalArgumentException("找不到 ruleset_version_id: " + normalizedRuleset));
            VersionedDataRelease release = releaseRepository.findDataRelease(dataReleaseId.trim())
                    .orElseThrow(() -> new IllegalArgumentException("找不到 data_release_id: " + dataReleaseId.trim()));
            if (!release.gameReleaseId().equals(ruleset.gameReleaseId())) {
                throw new IllegalArgumentException("ruleset_version_id 与 data_release_id 不属于同一 game_release");
            }
            if (!ruleset.enabled()) {
                throw new IllegalArgumentException("ruleset_version_id 未启用: " + normalizedRuleset);
            }
        }
        TeamLegalityChecker.CheckResult result = legalityChecker.check(
                dataReleaseId, normalizedRuleset, members);
        return new LegalityResult(dataReleaseId, normalizedRuleset,
                result.calculationVersion(), result.legal(), result.reason());
    }

    private void requireRelease(String releaseId) {
        if (releaseId == null || releaseId.isBlank()) throw new IllegalArgumentException("缺少 data_release_id");
        if (releaseRepository == null) return;
        VersionedDataRelease release = releaseRepository.findDataRelease(releaseId.trim())
                .orElseThrow(() -> new IllegalArgumentException("找不到 data_release_id: " + releaseId.trim()));
        String status = release.status();
        if (!releaseRepository.isQueryableRelease(release)) {
            throw new IllegalArgumentException("data_release 尚未通过审核: " + release.id());
        }
    }

    public record CalculationResult(String dataReleaseId, String calculationVersion, double value) { }

    public record DamageResult(String dataReleaseId, String calculationVersion,
                               int minDamage, int maxDamage, double modifier) {
        public DamageResult {
            if (minDamage < 0 || maxDamage < minDamage) {
                throw new IllegalArgumentException("伤害结果非法");
            }
        }
    }

    public record LegalityResult(String dataReleaseId, String rulesetVersionId,
                                 String calculationVersion, boolean legal, String reason) { }
}
