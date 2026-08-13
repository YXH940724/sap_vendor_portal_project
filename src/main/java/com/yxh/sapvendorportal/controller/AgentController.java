package com.yxh.sapvendorportal.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.yxh.sapvendorportal.agent.SupplierCollaborationAgent;
import com.yxh.sapvendorportal.common.security.VendorScopeResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/agent")
public class AgentController {
    private final SupplierCollaborationAgent agent;
    private final VendorScopeResolver scopeResolver;

    public AgentController(SupplierCollaborationAgent agent, VendorScopeResolver scopeResolver) {
        this.agent = agent;
        this.scopeResolver = scopeResolver;
    }

    @GetMapping("/status")
    public Map<String, Object> status() { return agent.status(); }

    @PostMapping("/chat")
    public Map<String, Object> chat(@RequestBody JsonNode input, HttpServletRequest request) {
        return agent.chat(scopeResolver.resolve(request), input);
    }
}
