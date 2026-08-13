package com.yxh.sapvendorportal.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.yxh.sapvendorportal.service.PortalService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** HTTP 接口层：只负责协议映射，门户业务编排由 PortalService 处理。 */
@RestController
@RequestMapping("/api")
public class PortalController {
    private final PortalService portalService;

    public PortalController(PortalService portalService) { this.portalService = portalService; }

    @GetMapping("/health") public Map<String, Object> health() { return portalService.health(); }
    @GetMapping("/session") public Map<String, String> session(HttpServletRequest request) { return portalService.session(request); }
    @GetMapping("/dashboard") public Map<String, Object> dashboard(HttpServletRequest request) { return portalService.dashboard(request); }
    @GetMapping("/data/{resourceName}") public Map<String, Object> data(@PathVariable String resourceName, @RequestParam(defaultValue = "") String search, @RequestParam(defaultValue = "30") int top, HttpServletRequest request) { return portalService.data(resourceName, search, top, request); }
    @GetMapping("/reconciliation") public Map<String, Object> reconciliation(HttpServletRequest request) { return portalService.reconciliation(request); }
    @PostMapping("/asns") @ResponseStatus(HttpStatus.CREATED) public Map<String, Object> createAsn(@RequestBody JsonNode input, HttpServletRequest request) { return portalService.createAsn(input, request); }
    @PostMapping("/invoices") @ResponseStatus(HttpStatus.CREATED) public Map<String, Object> createInvoice(@RequestBody JsonNode input, HttpServletRequest request) { return portalService.createInvoice(input, request); }
}
