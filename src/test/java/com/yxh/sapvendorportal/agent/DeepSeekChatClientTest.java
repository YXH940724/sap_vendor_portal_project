package com.yxh.sapvendorportal.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DeepSeekChatClientTest {
    @Test
    void explainsDeepSeekFailuresWithoutExposingResponseBody() {
        assertThat(DeepSeekChatClient.describeError(429)).contains("请求过于频繁");
        assertThat(DeepSeekChatClient.describeError(401)).contains("DEEPSEEK_API_KEY");
        assertThat(DeepSeekChatClient.describeError(502)).contains("HTTP 502");
    }
}
