package io.github.rodrigorjsf.agenticchat.guardrail.output;

import io.github.rodrigorjsf.agenticchat.tools.http.ApiEndpointProperties;
import io.github.rodrigorjsf.agenticchat.tools.http.LinkPolicy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.util.List;
import java.util.Set;

/**
 * The hosts a response is allowed to link to: every host in the tool catalogue,
 * plus anything explicitly added.
 *
 * <p>The invariant it protects is worth restating. A markdown image pointing at an
 * attacker's host fires the moment the answer renders, with no click — so the
 * question is not "is this link safe" but "did this link come from somewhere we
 * actually fetched from".
 *
 * <p><b>This class is now a façade.</b> The derivation itself moved to
 * {@link LinkPolicy}, in the tool package, and the reason is the second user rather
 * than tidiness: {@code ToolHttpClient} applies the same list to every body coming
 * <em>in</em>, because a link the answer may not carry is a link the model should
 * never have been shown. It could not stay here — {@code guardrail} already depends
 * on {@code tools} for {@link ApiEndpointProperties}, so a {@code tools → guardrail}
 * edge would close a package cycle that {@code ArchitectureTest#noPackageCycles}
 * fails on.
 *
 * <p><b>It holds the singleton, it does not re-derive it.</b> An earlier version
 * repeated the {@code @Value} for the extra-hosts property here and built its own
 * {@link LinkPolicy} from it, on the argument that Micronaut binds equal inputs to
 * both so they cannot disagree. That is true right up to the day someone renames
 * the property key on one side: the tool layer starts allowing a host, the
 * guardrail goes on blocking it, the user gets "The response was withheld by the
 * output policy." for a link this application put there itself, and nothing is red.
 * One object cannot disagree with itself —
 * {@code ToolHttpClientTest#theGuardrailAndTheToolLayerShareOneAllowList} asserts
 * the two sides are the same set, not merely equal ones.
 *
 * <p>The two-argument constructor stays because {@code GuardrailChainTest} builds
 * this by hand, and a façade that forces its callers to be rewritten is not a
 * façade. It is <em>not</em> the injection point: with two constructors Micronaut
 * needs to be told which one to use, hence {@code @Inject} on the other.
 */
@Singleton
public class LinkAllowList {

    private final LinkPolicy policy;

    @Inject
    public LinkAllowList(LinkPolicy policy) {
        this.policy = policy;
    }

    /**
     * For a test that assembles the guardrail chain by hand, with no context to take
     * the singleton from.
     */
    public LinkAllowList(List<ApiEndpointProperties> endpoints, List<String> extra) {
        this(new LinkPolicy(endpoints, extra));
    }

    public boolean allows(String host) {
        return policy.allows(host);
    }

    public Set<String> domains() {
        return policy.domains();
    }
}
