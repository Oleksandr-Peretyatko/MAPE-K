package org.anonymous.msrefactor.rt.analyze.selector;

/**
 * Naming helpers shared by the selectors.
 *
 * <p>Aggregate class names and event names are derived the same way regardless of operation, so
 * this lives outside any single selector.
 */
final class ServiceNaming {

    /** {@code "tournament"} → {@code "Tournament"} — service dir name to aggregate/event name prefix. */
    static String cap(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private ServiceNaming() {}
}
