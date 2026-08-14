package io.github.rodrigorjsf.agenticchat.triage;

import io.github.rodrigorjsf.agenticchat.skills.SkillCatalog;
import io.micrometer.core.instrument.MeterRegistry;
import io.micronaut.cache.annotation.CacheConfig;
import io.micronaut.cache.annotation.Cacheable;
import jakarta.inject.Singleton;

/**
 * An exact-match cache in front of the triage judge.
 *
 * <p>A separate bean rather than a {@code @Cacheable} method on
 * {@link TriageService}, because Micronaut's caching is proxy-based: a call from
 * one method of a bean to another method of the same bean does not pass through
 * the proxy, so an in-class {@code @Cacheable} would compile, run, and never cache
 * anything.
 *
 * <p>Caching a classifier is only correct because this one is stateless and runs at
 * temperature 0 — the same text always produces the same verdict. The cache key is
 * the normalized text alone; the skills index is the only other input and it is
 * fixed for the process lifetime.
 *
 * <p>The traffic that hits this hardest is repeated and adversarial: a probe sent
 * fifty times costs one model call.
 */
@Singleton
@CacheConfig("triage-verdicts")
public class CachedTriageJudge {

    private final TriageJudge judge;
    private final SkillCatalog skills;
    private final MeterRegistry meters;

    public CachedTriageJudge(TriageJudge judge, SkillCatalog skills, MeterRegistry meters) {
        this.judge = judge;
        this.skills = skills;
        this.meters = meters;
    }

    @Cacheable
    public TriageVerdict classify(String normalizedText) {
        meters.counter("agentic.triage.judge_calls").increment();
        return judge.classify(normalizedText, skills.availableSkillsBlock());
    }
}
