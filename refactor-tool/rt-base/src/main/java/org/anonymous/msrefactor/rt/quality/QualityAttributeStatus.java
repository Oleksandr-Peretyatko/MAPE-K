package org.anonymous.msrefactor.rt.quality;

/** Score band for a quality attribute score in [0..1]. */
public enum QualityAttributeStatus {
    POOR,
    WARNING,
    GOOD,
    EXCELLENT;

    public static QualityAttributeStatus of(double score) {
        if (score >= 0.85) return EXCELLENT;
        if (score >= 0.65) return GOOD;
        if (score >= 0.40) return WARNING;
        return POOR;
    }

    public String label() {
        return name().toLowerCase();
    }
}
