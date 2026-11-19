package org.anonymous.msrefactor.rt.config.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.anonymous.msrefactor.rt.analyze.AnalysisConfig;
import org.anonymous.msrefactor.rt.config.persistence.AnalysisConfigEntity;
import org.anonymous.msrefactor.rt.config.persistence.AnalysisConfigRepository;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * The default quality priorities live in a nullable JSON column: NULL means "factory defaults",
 * a value round-trips in order, and saving a config without the field keeps the column NULL so
 * old rows and omitted fields stay on the factory default.
 */
class AnalysisConfigServiceTest {

    private AnalysisConfigRepository repository;
    private AnalysisConfigService service;

    @BeforeEach
    void setUp() {
        repository = mock(AnalysisConfigRepository.class);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        service = new AnalysisConfigService(repository, new ObjectMapper());
    }

    @Test
    @DisplayName("factory defaults carry the previously hardcoded priority order")
    void defaultsCarryPriorityOrder() {
        assertThat(AnalysisConfig.defaults().getDefaultQualityPriorities())
                .containsExactly("PERFORMANCE", "CONSISTENCY", "MAINTAINABILITY");
    }

    @Test
    @DisplayName("a stored priority list round-trips in order through init")
    void storedPrioritiesRoundTrip() {
        AnalysisConfig stored = AnalysisConfig.defaults();
        stored.setDefaultQualityPriorities(List.of("SCALABILITY", "PERFORMANCE"));
        AnalysisConfig saved = service.save(stored);

        assertThat(saved.getDefaultQualityPriorities())
                .containsExactly("SCALABILITY", "PERFORMANCE");

        AnalysisConfigEntity entity = captureSavedEntity();
        when(repository.findById(any())).thenReturn(Optional.of(entity));
        service.init();
        assertThat(service.current().getDefaultQualityPriorities())
                .containsExactly("SCALABILITY", "PERFORMANCE");
    }

    @Test
    @DisplayName("a NULL column deserializes to the factory default priorities")
    void nullColumnFallsBackToDefaults() {
        service.save(AnalysisConfig.defaults());
        AnalysisConfigEntity entity = captureSavedEntity();
        entity.setQualityPrioritiesJson(null);

        when(repository.findById(any())).thenReturn(Optional.of(entity));
        service.init();
        assertThat(service.current().getDefaultQualityPriorities())
                .isEqualTo(AnalysisConfig.defaultQualityPriorityNames());
    }

    @Test
    @DisplayName("saving a config without priorities keeps the column NULL and serves defaults")
    void nullFieldKeepsColumnNullAndServesDefaults() {
        AnalysisConfig cfg = AnalysisConfig.defaults();
        cfg.setDefaultQualityPriorities(null);
        AnalysisConfig effective = service.save(cfg);

        assertThat(captureSavedEntity().getQualityPrioritiesJson()).isNull();
        assertThat(effective.getDefaultQualityPriorities())
                .isEqualTo(AnalysisConfig.defaultQualityPriorityNames());
        assertThat(service.current().getDefaultQualityPriorities())
                .isEqualTo(AnalysisConfig.defaultQualityPriorityNames());
    }

    private AnalysisConfigEntity captureSavedEntity() {
        var captor = org.mockito.ArgumentCaptor.forClass(AnalysisConfigEntity.class);
        verify(repository, atLeastOnce()).save(captor.capture());
        return captor.getValue();
    }
}
