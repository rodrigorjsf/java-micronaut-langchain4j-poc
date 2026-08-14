package io.github.rodrigorjsf.agenticchat.tools.network;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import io.github.rodrigorjsf.agenticchat.skills.SkillTools;
import io.github.rodrigorjsf.agenticchat.tools.http.ToolHttpClient;
import io.github.rodrigorjsf.agenticchat.tools.http.ToolJson;
import io.github.rodrigorjsf.agenticchat.tools.http.ToolResponse;
import jakarta.inject.Singleton;

import java.util.Locale;
import java.util.Map;

/**
 * IP address lookups and name-based population statistics, disclosed by the
 * {@code network-and-identity} skill.
 *
 * <p>Every tool in this class returns an <em>approximation that reads like a
 * fact</em>, and that is the design problem the whole file is organised around.
 * An IP address resolves to where a network block is registered, which can be a
 * different city — sometimes a different country — from wherever the machine
 * using it sits, and says nothing at all about a person. A name statistic is a
 * frequency over millions of records, and the individual in front of the user is
 * a single sample that the statistic cannot describe. So each tool keeps the
 * evidence that shows how weak its own inference is: {@code probability} and
 * {@code count} on the name tools, the registered ISP and autonomous system on
 * the address tools, and a description that states the limit rather than leaving
 * it to the skill body.
 *
 * <p>Three further decisions:
 *
 * <ul>
 *   <li><b>One geolocation provider, and the faster one was the one dropped.</b>
 *       ip-api answered in 0.27 s to ipwho.is's 0.27 s and carried the same
 *       fields, but only over plain HTTP: probed on 2026-08-14 its HTTPS endpoint
 *       returned 403, because TLS is a paid feature there. The argument this tool
 *       takes is a user-supplied IP address, and putting that on the wire in clear
 *       text to save a few bytes is not a trade worth making. It is recorded as a
 *       rejected control in {@code docs/03-security.md} rather than left as an
 *       absence someone re-adds later.</li>
 *   <li><b>Unroutable addresses are refused here, not upstream.</b> {@code
 *       10.0.0.5}, {@code 127.0.0.1} and {@code fe80::} have no location to
 *       return, and asking anyway spends one request from a daily allowance to
 *       receive an error. Refusing locally answers in the model's own language
 *       and costs nothing.</li>
 *   <li><b>Bodies are returned whole wherever they are already small.</b> The
 *       name APIs answered 41 to 260 bytes in probing, so there is nothing to
 *       project away. {@link ToolJson} earns its place only on ipwho.is, whose
 *       669-byte record carries a flag block and a timezone object nothing here
 *       reads.</li>
 * </ul>
 *
 * <p>All three name services answer {@code 200} with {@code "count": 0} for a
 * name they have never seen, so "no data" never reaches
 * {@link ToolHttpClient}'s status mapping. It is turned into an explicit
 * sentence below. Projecting those bodies would make it worse rather than
 * better: {@link ToolJson} drops null fields, so a miss would arrive as
 * {@code {"name":"x","probability":0}} — a partial answer rather than an absent
 * one.
 *
 * <p>One capping limitation is accepted knowingly. {@code
 * guess_nationality_from_name} returns its countries in an array nested inside
 * an object, and {@link ToolJson#projectCapped} caps only a <em>top-level</em>
 * array, so the source catalogue's "cap 3" is not expressible with the shared
 * helper. At 260 bytes for five countries that is not a token emergency, and
 * hand-rolling a second slicing path for one tool would cost more than it saves.
 */
@Singleton
public class NetworkIdentityTools implements SkillTools {

    private static final String IPWHOIS = "ipwhois";
    private static final String GENDERIZE = "genderize";
    private static final String AGIFY = "agify";
    private static final String NATIONALIZE = "nationalize";

    /** A first name, in any script, without digits or punctuation beyond a hyphen or apostrophe. */
    private static final String NAME_PATTERN = "\\p{L}[\\p{L}'\\- ]{0,39}";

    private final ToolHttpClient http;
    private final ToolJson json;

    public NetworkIdentityTools(ToolHttpClient http, ToolJson json) {
        this.http = http;
        this.json = json;
    }

    @Override
    public String skillName() {
        return "network-and-identity";
    }

    @Tool("""
            Look up which country and city a public IP address is registered in, with \
            its coordinates, internet provider, organisation and autonomous system. \
            Use when the user gives an IP address, or asks where a server, a website's \
            host or a log entry connects from. This locates a network registration, \
            which is approximate and is never a person's whereabouts. Requires a public \
            address: private, loopback and link-local ranges are refused.""")
    public String geolocate_ip(
            @P("The public IP address to look up, IPv4 or IPv6, e.g. 8.8.8.8 or 2001:4860:4860::8888. Required: this tool cannot resolve 'my IP'.")
            String ip) {
        String address = normalised(ip);
        String complaint = complaintAbout(address);
        if (complaint != null) {
            return complaint;
        }
        // success is kept on purpose: it is false on a failed lookup, and a boolean
        // false survives projection where the null fields around it do not.
        var response = json.project(http.get(IPWHOIS, "/" + address),
                "ip",
                "success",
                "country",
                "city",
                "latitude",
                "longitude",
                "connection.isp",
                "connection.org",
                "connection.asn");
        return orNotLocated(response, "\"success\":false", address);
    }

    @Tool("""
            Get how often a first name belongs to men and to women across a large \
            international name database, with the probability and the number of records \
            behind it. Use only when the user explicitly asks what gender a name is \
            usually associated with. This is a statistic about the name in a population, \
            never a fact about an individual who carries it, and the probability and \
            count must be reported with it.""")
    public String guess_gender_from_name(
            @P("One first name on its own, letters only, e.g. 'Rodrigo' or 'Maria'. Not a full name, not a surname, not a sentence.")
            String name) {
        String given = normalised(name);
        if (!given.matches(NAME_PATTERN)) {
            return nameComplaint(name);
        }
        // 67 bytes probed: name, gender, probability and count, all of them load-bearing.
        return orNoStatistic(http.get(GENDERIZE, "", Map.of("name", given)), given, "gender");
    }

    @Tool("""
            Get the mean age of the people recorded under a first name in a large \
            international name database, with the number of records behind it. Use only \
            when the user explicitly asks how old a name sounds or which generation uses \
            it. This is an average over a population and says nothing about the age of \
            any particular person with that name; report the count alongside it.""")
    public String guess_age_from_name(
            @P("One first name on its own, letters only, e.g. 'Rodrigo' or 'Maria'. Not a full name and not a surname.")
            String name) {
        String given = normalised(name);
        if (!given.matches(NAME_PATTERN)) {
            return nameComplaint(name);
        }
        // 41 bytes probed. Nothing to project away.
        return orNoStatistic(http.get(AGIFY, "", Map.of("name", given)), given, "age");
    }

    @Tool("""
            Get the countries where a first name is most common, each with the share of \
            records it accounts for, from a large international name database. Use only \
            when the user explicitly asks where a name comes from or which country it \
            suggests. Common names appear across many countries, so treat the shares as \
            what they are — a distribution over a database, never the nationality or \
            origin of a person.""")
    public String guess_nationality_from_name(
            @P("One first name on its own, letters only, e.g. 'Rodrigo' or 'Maria'. Not a surname and not a full name.")
            String name) {
        String given = normalised(name);
        if (!given.matches(NAME_PATTERN)) {
            return nameComplaint(name);
        }
        // 260 bytes probed for five countries. The array is nested inside the object,
        // which ToolJson cannot cap, and at this size does not need capping.
        return orNoStatistic(http.get(NATIONALIZE, "", Map.of("name", given)), given, "country");
    }

    // ------------------------------------------------------------------

    private static String normalised(String raw) {
        return raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
    }

    /**
     * Checks an address before it becomes a path segment.
     *
     * <p>Two reasons this happens here rather than upstream. An unvalidated value
     * on a path can be an illegal URI, and an illegal URI throws before the request
     * is attempted — which would escape the tool as an exception. And an address in
     * a private, loopback or link-local range has no location to return at all, so
     * asking would spend one of a very small per-minute budget to be told so.
     *
     * @return {@code null} when the address may be looked up, otherwise the sentence
     *         to hand back to the model
     */
    private static String complaintAbout(String address) {
        if (address.isEmpty()) {
            return "Invalid argument: a public IP address is required, e.g. 8.8.8.8. This tool cannot "
                    + "resolve 'my IP' — an empty value would return this server's own address. Ask the "
                    + "user to paste the address they want looked up.";
        }
        if (address.indexOf(':') >= 0) {
            if (!address.matches("[0-9a-f:]{2,45}")) {
                return malformed(address);
            }
            boolean reserved = address.equals("::")
                    || address.startsWith("::1")
                    || address.startsWith("fe80")
                    || address.startsWith("fc")
                    || address.startsWith("fd");
            return reserved ? unroutable(address) : null;
        }
        String[] parts = address.split("\\.", -1);
        if (parts.length != 4) {
            return malformed(address);
        }
        int[] octets = new int[4];
        for (int i = 0; i < 4; i++) {
            if (!parts[i].matches("\\d{1,3}")) {
                return malformed(address);
            }
            octets[i] = Integer.parseInt(parts[i]);
            if (octets[i] > 255) {
                return malformed(address);
            }
        }
        return isReserved(octets) ? unroutable(address) : null;
    }

    /** Loopback, the private blocks of RFC 1918, carrier-grade NAT, link-local, multicast and above. */
    private static boolean isReserved(int[] octets) {
        return octets[0] == 0
                || octets[0] == 10
                || octets[0] == 127
                || octets[0] >= 224
                || (octets[0] == 169 && octets[1] == 254)
                || (octets[0] == 172 && octets[1] >= 16 && octets[1] <= 31)
                || (octets[0] == 192 && octets[1] == 168)
                || (octets[0] == 100 && octets[1] >= 64 && octets[1] <= 127);
    }

    private static String malformed(String address) {
        return "Invalid argument: '" + address + "' is not an IP address. Expected four numbers "
                + "0-255 separated by dots, e.g. 8.8.8.8, or an IPv6 address such as "
                + "2001:4860:4860::8888. A hostname like example.com is not accepted here — ask the "
                + "user for the address itself.";
    }

    private static String unroutable(String address) {
        return "Invalid argument: '" + address + "' is a private, loopback or link-local address. "
                + "Those exist inside every network and carry no location at all, so no lookup can "
                + "place them. Tell the user this address is internal, and ask for the public address "
                + "if they wanted one located.";
    }

    private static String nameComplaint(String name) {
        return "Invalid argument: expected a single first name in letters, e.g. 'Rodrigo', got '"
                + name + "'. Surnames, full names, digits and sentences are not in this database. "
                + "Send only the given name, or ask the user which name they mean.";
    }

    /**
     * ipwho.is answers {@code 200} with {@code "success":false} for an address it
     * cannot place, so the failure never reaches the HTTP status mapping. Handing
     * that body to the model is worse than an error, because it reads as a real
     * record with the interesting fields missing.
     */
    private static String orNotLocated(ToolResponse response, String marker, String address) {
        if (!response.isOk()) {
            return response.toModelText();
        }
        String text = response.toModelText();
        if (!carries(text, marker)) {
            return text;
        }
        return "No result: there is no registration on file for " + address
                + ". Tell the user the address could not be located; do not guess a country, and "
                + "do not call this tool again with the same address.";
    }

    /**
     * The three name services report an unknown name as {@code "count": 0} with a
     * {@code 200}, and the field that was asked for comes back null. Said plainly
     * here, because a model handed {@code {"gender":null}} tends to answer from the
     * spelling of the name instead of reporting that the data has nothing.
     *
     * <p>The empty {@code country} array is checked as well as the count. The
     * nationality service is the one of the three whose miss body was never
     * captured in the source catalogue, and an empty list is its unambiguous
     * signal either way — a name it does know always carries at least one country.
     */
    private static String orNoStatistic(ToolResponse response, String name, String what) {
        if (!response.isOk()) {
            return response.toModelText();
        }
        String text = response.toModelText();
        if (!carries(text, "\"count\":0") && !carries(text, "\"country\":[]")) {
            return text;
        }
        return "No result: the name database holds no records for '" + name + "', so there is no "
                + what + " statistic for it. Tell the user the name is not in the data; do not infer a "
                + what + " from how the name looks or sounds.";
    }

    /** Compares against a whitespace-free copy so pretty-printed JSON matches too. */
    private static boolean carries(String text, String marker) {
        return text.replace(" ", "").contains(marker);
    }
}
