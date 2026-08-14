package io.github.rodrigorjsf.agenticchat.guardrail.input;

/**
 * What the classifier concluded about one piece of user text.
 *
 * @param label      the classification
 * @param confidence 0..1; the triage guardrail only acts on a high-confidence
 *                   INJECTION, so an unsure classifier lets the request through
 *                   rather than blocking a real user
 * @param reason     short, for the audit log — never shown to the user, because a
 *                   detector that explains itself teaches the attacker
 */
public record InjectionVerdict(Label label, double confidence, String reason) {

    public enum Label {
        BENIGN,
        INJECTION
    }

    public static InjectionVerdict benign(String reason) {
        return new InjectionVerdict(Label.BENIGN, 1.0, reason);
    }

    public boolean isConfidentInjection(double threshold) {
        return label == Label.INJECTION && confidence >= threshold;
    }
}
