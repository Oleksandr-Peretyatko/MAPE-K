package org.anonymous.msrefactor.rt.quality;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StatusMappingTest {

    @Test
    void belowPoorBoundary_isPoor() {
        assertThat(QualityAttributeStatus.of(0.0)).isEqualTo(QualityAttributeStatus.POOR);
        assertThat(QualityAttributeStatus.of(0.39)).isEqualTo(QualityAttributeStatus.POOR);
    }

    @Test
    void warningBand() {
        assertThat(QualityAttributeStatus.of(0.40)).isEqualTo(QualityAttributeStatus.WARNING);
        assertThat(QualityAttributeStatus.of(0.64)).isEqualTo(QualityAttributeStatus.WARNING);
    }

    @Test
    void goodBand() {
        assertThat(QualityAttributeStatus.of(0.65)).isEqualTo(QualityAttributeStatus.GOOD);
        assertThat(QualityAttributeStatus.of(0.84)).isEqualTo(QualityAttributeStatus.GOOD);
    }

    @Test
    void excellentBand() {
        assertThat(QualityAttributeStatus.of(0.85)).isEqualTo(QualityAttributeStatus.EXCELLENT);
        assertThat(QualityAttributeStatus.of(1.0)).isEqualTo(QualityAttributeStatus.EXCELLENT);
    }

    @Test
    void labels_areLowerCase() {
        assertThat(QualityAttributeStatus.POOR.label()).isEqualTo("poor");
        assertThat(QualityAttributeStatus.WARNING.label()).isEqualTo("warning");
        assertThat(QualityAttributeStatus.GOOD.label()).isEqualTo("good");
        assertThat(QualityAttributeStatus.EXCELLENT.label()).isEqualTo("excellent");
    }
}
