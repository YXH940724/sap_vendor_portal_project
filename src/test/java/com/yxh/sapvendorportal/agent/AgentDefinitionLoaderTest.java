package com.yxh.sapvendorportal.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

class AgentDefinitionLoaderTest {
    private final AgentDefinitionLoader loader = new AgentDefinitionLoader(new ObjectMapper());

    @Test
    void loadsEditableSkillAndMcpToolCatalogue() {
        assertThat(loader.systemPrompt()).contains("供应商协同 Agent").contains("当前登录供应商");
        assertThat(StreamSupport.stream(loader.toolDefinitions().spliterator(), false)
                .map(tool -> tool.path("function").path("name").asText()))
                .containsExactly("query_purchase_orders", "query_asn_status", "query_settlement_candidates", "prepare_asn_draft", "prepare_invoice_draft");
    }
}
