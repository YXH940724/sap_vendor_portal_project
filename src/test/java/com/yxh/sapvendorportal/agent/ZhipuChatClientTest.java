package com.yxh.sapvendorportal.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ZhipuChatClientTest {
    @Test
    void explainsZhipuRateLimitErrorWithoutExposingResponseBody() {
        assertThat(ZhipuChatClient.describeError(429, "{\"error\":{\"code\":\"1302\",\"message\":\"rate limited\"}}"))
                .contains("速率限制")
                .doesNotContain("rate limited");
    }

    @Test
    void explainsModelPermissionError() {
        assertThat(ZhipuChatClient.describeError(429, "{\"error\":{\"code\":\"1311\"}}"))
                .contains("模型权限");
    }
}
