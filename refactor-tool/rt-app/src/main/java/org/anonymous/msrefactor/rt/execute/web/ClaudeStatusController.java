package org.anonymous.msrefactor.rt.execute.web;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.anonymous.msrefactor.rt.execute.dto.ClaudeAuthStatus;
import org.anonymous.msrefactor.rt.execute.service.ClaudeAuthService;

@RestController
@RequestMapping("/claude")
@RequiredArgsConstructor
public class ClaudeStatusController {

    private final ClaudeAuthService claudeAuthService;

    /**
     * Whether the Claude session is usable. Always 200: the verdict is in {@code state}.
     * Cached for {@code rt.claude.status-cache-seconds}; {@code refresh=true} asks the CLI again.
     */
    @GetMapping("/status")
    public ClaudeAuthStatus status(@RequestParam(defaultValue = "false") boolean refresh) {
        return claudeAuthService.check(refresh);
    }
}
