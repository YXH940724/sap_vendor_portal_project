package com.yxh.sapvendorportal.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yxh.sapvendorportal.config.PortalProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** OpenAI-compatible DeepSeek Chat Completions client with Function Calling support. */
@Component
public class DeepSeekChatClient {
    private final PortalProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public DeepSeekChatClient(PortalProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    public boolean configured() { return properties.getAi().isConfigured(); }
    public String configurationIssue() { return properties.getAi().configurationIssue(); }

    public Completion complete(ArrayNode messages, ArrayNode tools) {
        if (!configured()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, configurationIssue());
        ObjectNode request = objectMapper.createObjectNode();
        request.put("model", properties.getAi().getModel().trim().toLowerCase(Locale.ROOT));
        request.set("messages", messages);
        request.set("tools", tools);
        request.put("tool_choice", "auto");
        request.put("temperature", 0.2);
        request.put("max_tokens", 900);
        try {
            HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(properties.getAi().getBaseUrl()))
                    .timeout(Duration.ofSeconds(45))
                    .header("Authorization", "Bearer " + properties.getAi().getApiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(request), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, describeError(response.statusCode()));
            JsonNode message = objectMapper.readTree(response.body()).path("choices").path(0).path("message");
            if (!message.isObject()) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "DeepSeek AI 未返回可用回答。");
            ObjectNode assistant = objectMapper.createObjectNode();
            assistant.put("role", "assistant");
            if (message.hasNonNull("content")) assistant.set("content", message.get("content"));
            if (message.path("tool_calls").isArray()) assistant.set("tool_calls", message.path("tool_calls"));
            List<ToolCall> toolCalls = new ArrayList<>();
            for (JsonNode call : message.path("tool_calls")) {
                String id = call.path("id").asText();
                String name = call.path("function").path("name").asText();
                String arguments = call.path("function").path("arguments").asText("{}");
                if (!id.isBlank() && !name.isBlank()) toolCalls.add(new ToolCall(id, name, arguments));
            }
            return new Completion(message.path("content").asText(""), assistant, toolCalls);
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "无法连接 DeepSeek AI 服务，请检查网络与 DEEPSEEK_API_KEY。", exception);
        }
    }

    static String describeError(int statusCode) {
        return switch (statusCode) {
            case 400 -> "DeepSeek AI 请求参数无效，请检查模型名称和工具定义。";
            case 401 -> "DeepSeek API Key 无效或未授权，请检查 DEEPSEEK_API_KEY。";
            case 402, 403 -> "DeepSeek 账户余额不足或没有当前模型权限，请在 DeepSeek 控制台核对额度和权限。";
            case 429 -> "DeepSeek AI 请求过于频繁或账户额度受限，请稍后重试并检查控制台限额。";
            default -> "DeepSeek AI 请求失败（HTTP " + statusCode + "），请稍后重试或检查服务状态。";
        };
    }

    public record ToolCall(String id, String name, String arguments) { }
    public record Completion(String content, ObjectNode assistantMessage, List<ToolCall> toolCalls) { }
}
