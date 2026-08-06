package com.yxh.sapvendorportal.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Loads the editable runtime definition of the supplier collaboration agent. */
@Component
public class AgentDefinitionLoader {
    static final String SKILL_RESOURCE = "ai/skills/supplier-collaboration-agent.md";
    static final String TOOL_RESOURCE = "ai/mcp-tools.json";

    private final ObjectMapper objectMapper;

    public AgentDefinitionLoader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String systemPrompt() {
        return readText(SKILL_RESOURCE);
    }

    public ArrayNode toolDefinitions() {
        try {
            JsonNode tools = objectMapper.readTree(new ClassPathResource(TOOL_RESOURCE).getInputStream());
            if (!tools.isArray()) throw new IllegalStateException("MCP 工具目录必须是 JSON 数组。");
            return (ArrayNode) tools.deepCopy();
        } catch (IOException exception) {
            throw new IllegalStateException("无法读取 MCP 工具目录：" + TOOL_RESOURCE, exception);
        }
    }

    private String readText(String resource) {
        try {
            return new String(new ClassPathResource(resource).getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (IOException exception) {
            throw new IllegalStateException("无法读取 Agent Skill：" + resource, exception);
        }
    }
}
