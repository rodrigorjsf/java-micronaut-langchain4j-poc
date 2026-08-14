package io.github.rodrigorjsf.agenticchat.guardrail.output;

import io.github.rodrigorjsf.agenticchat.tools.http.ApiEndpointProperties;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The hosts a response is allowed to link to: every host in the tool catalogue,
 * plus anything explicitly added.
 *
 * <p><b>Derived, not listed.</b> A hand-written allow-list beside a growing tool
 * catalogue drifts in the direction that hurts: a new data source is added, its
 * host is not, and the assistant starts refusing to cite the source it just used.
 * Deriving it means the two cannot disagree.
 *
 * <p>The invariant it protects is worth restating. A markdown image pointing at an
 * attacker's host fires the moment the answer renders, with no click — so the
 * question is not "is this link safe" but "did this link come from somewhere we
 * actually fetched from".
 *
 * <p>Registrable-domain matching, not exact-host: a tool calling
 * {@code api.open-meteo.com} makes {@code open-meteo.com} and its subdomains
 * linkable, because a source's documentation and its API rarely live on the same
 * hostname.
 */
@Singleton
public class LinkAllowList {

    private static final Logger LOG = LoggerFactory.getLogger(LinkAllowList.class);

    private final Set<String> allowed;

    public LinkAllowList(List<ApiEndpointProperties> endpoints,
                         @Value("${agentic.guardrails.output.extra-allowed-link-hosts:}") List<String> extra) {
        var hosts = new LinkedHashSet<String>();
        for (ApiEndpointProperties endpoint : endpoints) {
            String host = hostOf(endpoint.baseUrl());
            if (host != null) {
                hosts.add(registrableDomain(host));
            }
        }
        if (extra != null) {
            extra.stream()
                    .filter(host -> host != null && !host.isBlank())
                    .map(host -> host.toLowerCase(Locale.ROOT).strip())
                    .forEach(hosts::add);
        }
        this.allowed = Set.copyOf(hosts);
        LOG.info("Responses may link to {} domains, derived from the tool catalogue", allowed.size());
    }

    public boolean allows(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        String bare = host.toLowerCase(Locale.ROOT);
        bare = bare.contains("@") ? bare.substring(bare.indexOf('@') + 1) : bare;
        bare = bare.split(":")[0];
        for (String domain : allowed) {
            if (bare.equals(domain) || bare.endsWith("." + domain)) {
                return true;
            }
        }
        return false;
    }

    public Set<String> domains() {
        return allowed;
    }

    private static String hostOf(String baseUrl) {
        try {
            return URI.create(baseUrl).getHost();
        } catch (RuntimeException notAUri) {
            return null;
        }
    }

    /**
     * Last two labels, which is right for {@code api.open-meteo.com} and wrong for
     * a multi-label public suffix such as {@code .com.br}. Three labels are kept
     * when the second-to-last is one of the handful of suffixes this catalogue
     * actually contains — a full public-suffix list is a dependency and a download
     * for a problem this size.
     */
    private static String registrableDomain(String host) {
        String[] labels = host.toLowerCase(Locale.ROOT).split("\\.");
        if (labels.length <= 2) {
            return host.toLowerCase(Locale.ROOT);
        }
        String secondLast = labels[labels.length - 2];
        boolean multiLabelSuffix = Set.of("com", "gov", "org", "net", "edu").contains(secondLast)
                && labels[labels.length - 1].length() == 2;
        int keep = multiLabelSuffix ? 3 : 2;
        return String.join(".", java.util.Arrays.copyOfRange(labels, labels.length - keep, labels.length));
    }
}
