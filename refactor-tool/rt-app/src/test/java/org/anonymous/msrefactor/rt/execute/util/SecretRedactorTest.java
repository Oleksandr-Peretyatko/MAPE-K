package org.anonymous.msrefactor.rt.execute.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SecretRedactorTest {

    private static final String PAT = "github_pat_11AAAAAAA0abcdefghijklmn_OPQRSTUVWXYZ0123456789abcdefghij";

    @Test
    @DisplayName("masks the userinfo of a URL with an embedded token (git remote -v under insteadOf)")
    void urlUserinfo() {
        String remote = "origin\thttps://" + PAT + "@github.com/anonymous-org/repo.git (fetch)";

        assertThat(SecretRedactor.redact(remote, null))
                .isEqualTo("origin\thttps://***@github.com/anonymous-org/repo.git (fetch)")
                .doesNotContain("github_pat_");
    }

    @Test
    @DisplayName("masks bare GitHub tokens: fine-grained and classic formats")
    void bareTokens() {
        String text = "token=" + PAT + " classic=ghp_abcdefghijklmnopqrstuvwxyz0123456789";

        assertThat(SecretRedactor.redact(text, null)).isEqualTo("token=*** classic=***");
    }

    @Test
    @DisplayName("masks the literal secret even when it matches no known format")
    void literalSecret() {
        assertThat(SecretRedactor.redact("Authorization failed for s3cr3t-value", "s3cr3t-value"))
                .isEqualTo("Authorization failed for ***");
    }

    @Test
    @DisplayName("leaves text without secrets unchanged, including URLs without userinfo")
    void noSecrets() {
        String text = "origin\thttps://github.com/anonymous-org/repo.git (fetch) | user@example.com";

        assertThat(SecretRedactor.redact(text, null)).isEqualTo(text);
        assertThat(SecretRedactor.redact(null, null)).isNull();
        assertThat(SecretRedactor.redact("", "x")).isEmpty();
    }
}
