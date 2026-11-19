package org.anonymous.msrefactor.rt.monitor.web.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PipelineStartRequest {
    private String host;
    private int windowMinutes;
    private String iterationId;
}
