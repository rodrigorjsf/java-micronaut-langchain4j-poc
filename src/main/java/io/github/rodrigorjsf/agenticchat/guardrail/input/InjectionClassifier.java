package io.github.rodrigorjsf.agenticchat.guardrail.input;

/**
 * Second opinion on text the deterministic rules found suspicious but not
 * conclusive.
 *
 * <p>An interface rather than a direct AI service call so the gray-zone path can
 * be tested without a model, and so the classifier can be swapped or disabled
 * without touching the guardrail.
 */
public interface InjectionClassifier {

    InjectionVerdict classify(String normalizedText);
}
