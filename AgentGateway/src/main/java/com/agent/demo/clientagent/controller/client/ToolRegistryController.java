package com.agent.demo.clientagent.controller.client;

import com.agent.demo.clientagent.client_registry.ClientRegistration;
import com.agent.demo.clientagent.client_registry.ClientRegistry;
import com.agent.demo.clientagent.client_registry.ClientType;
import com.agent.demo.clientagent.client_registry.ToolDefinition;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 客户端注册与工具管理入口：服务端客户端和移动端客户端都走同一套接口。
 * 一个客户端(clientId)拥有自己的工具目录，后续接入鉴权后 clientId 由凭证解析，不再信任请求体自报。
 */
@RestController
@RequestMapping("/clients")
public class ToolRegistryController {

    /**
     * CAP register_tools 请求信封。
     *
     * @param v       协议版本，固定 "0.1"
     * @param id      请求 ID，响应原样带回
     * @param type    消息类型，固定 "register_tools"
     * @param payload 注册业务字段
     */
    public record RegisterEnvelope(String v, String id, String type, RegisterPayload payload) {
    }

    /**
     * 注册业务字段。
     *
     * @param clientId            客户端唯一标识
     * @param clientType          SERVER 或 MOBILE
     * @param baseCallbackUrl     SERVER 必填，网关回调基地址
     * @param defaultCallbackPath 可选，默认回调路径
     * @param clientPrompt        可选，客户端系统提示词
     * @param tools               工具列表，不能为空
     */
    public record RegisterPayload(String clientId,
                                  String clientType,
                                  String baseCallbackUrl,
                                  String defaultCallbackPath,
                                  String clientPrompt,
                                  List<ToolRegistration> tools) {
    }

    /**
     * 单个工具注册项。
     *
     * @param name         工具名，客户端内唯一
     * @param description  给模型看的用途描述
     * @param callbackPath 可选，工具级 HTTP 回调路径
     * @param inputSchema  标准 JSON Schema，描述参数与必填项
     */
    public record ToolRegistration(String name,
                                   String description,
                                   String callbackPath,
                                   Map<String, Object> inputSchema) {
    }

    /**
     * tools_ack 响应信封。
     *
     * @param v       协议版本
     * @param id      对应请求 ID
     * @param type    固定 "tools_ack"
     * @param payload 注册结果
     */
    public record ToolsAckEnvelope(String v, String id, String type, ToolsAckPayload payload) {
    }

    /**
     * tools_ack 的业务字段。
     *
     * @param clientId   注册的客户端
     * @param registered 本次注册的工具数量
     */
    public record ToolsAckPayload(String clientId, int registered) {
    }

    /**
     * error 响应信封。
     *
     * @param v       协议版本
     * @param id      对应请求 ID；无法解析时可为 null
     * @param type    固定 "error"
     * @param payload 错误详情
     */
    public record ErrorEnvelope(String v, String id, String type, ErrorPayload payload) {
    }

    /**
     * error 的业务字段。
     *
     * @param code    错误码，当前为 INVALID_REQUEST
     * @param message 可读错误信息
     */
    public record ErrorPayload(String code, String message) {
    }

    /** 客户端与工具目录的存储/路由入口。 */
    private final ClientRegistry clientRegistry;

    public ToolRegistryController(ClientRegistry clientRegistry) {
        this.clientRegistry = clientRegistry;
    }

    /**
     * POST /clients/register
     *
     * <p>作用：客户端注册/覆盖自己的工具目录。同一 {@code clientId} 再次注册会全量替换，
     * 未出现在本次列表中的工具视为删除。
     *
     * <p>入参（CAP {@code register_tools} 信封的 payload）：
     * <ul>
     *   <li>{@code clientId}：必填，客户端唯一标识；</li>
     *   <li>{@code clientType}：必填，{@code SERVER} 或 {@code MOBILE}；</li>
     *   <li>{@code baseCallbackUrl}：SERVER 必填，网关回调客户端的基地址，必须是 http/https；</li>
     *   <li>{@code defaultCallbackPath}：可选，工具未指定 callbackPath 时使用；</li>
     *   <li>{@code clientPrompt}：可选，注入给 Agent 的客户端系统提示词；</li>
     *   <li>{@code tools[]}：必填且非空，每个工具包含：
     *     <ul>
     *       <li>{@code name}：工具名，客户端内唯一；</li>
     *       <li>{@code description}：给模型看的用途描述；</li>
     *       <li>{@code inputSchema}：必填，标准 JSON Schema；</li>
     *       <li>{@code callbackPath}：可选，仅 SERVER 客户端使用。</li>
     *     </ul>
     *   </li>
     * </ul>
     *
     * <p>返回 200 {@code tools_ack} 信封：payload 含 {@code clientId}、{@code registered}（注册数量）。
     *
     * <p>错误 400 {@code error} 信封：{@code code=INVALID_REQUEST}，{@code message} 说明原因。
     *
     * <p>请求体示例（SERVER）：
     * <pre>
     * {
     *   "v":"0.1","id":"reg-1","type":"register_tools",
     *   "payload":{
     *     "clientId":"xiaohongshu","clientType":"SERVER",
     *     "baseCallbackUrl":"https://client.example.com",
     *     "defaultCallbackPath":"/client/tools/execute",
     *     "clientPrompt":"你是小红书的创作助手...",
     *     "tools":[{
     *       "name":"get_user_profile",
     *       "description":"查当前用户资料",
     *       "inputSchema":{"type":"object","properties":{},"additionalProperties":false}
     *     }]
     *   }
     * }
     * </pre>
     */
    @PostMapping("/register")
    public ToolsAckEnvelope register(@RequestBody RegisterEnvelope request) {
        if (!"register_tools".equals(request.type())) {
            throw new IllegalArgumentException("type 必须是 register_tools");
        }
        RegisterPayload payload = request.payload();
        if (payload == null) {
            throw new IllegalArgumentException("payload 不能为空");
        }

        String clientId = trim(payload.clientId());
        String clientTypeStr = trim(payload.clientType());
        if (clientId.isEmpty()) {
            throw new IllegalArgumentException("clientId不能为空");
        }
        ClientType clientType;
        try {
            clientType = ClientType.valueOf(clientTypeStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("clientType非法，必须是 SERVER 或 MOBILE: " + clientTypeStr);
        }

        List<ToolRegistration> tools = payload.tools();
        if (tools == null || tools.isEmpty()) {
            throw new IllegalArgumentException("tools不能为空");
        }
        List<ToolDefinition> definitions = new ArrayList<>();
        for (ToolRegistration tool : tools) {
            String name = trim(tool.name());
            if (tool.inputSchema() == null) {
                throw new IllegalArgumentException("工具 " + name + " 缺少 inputSchema");
            }
            definitions.add(new ToolDefinition(
                    name,
                    tool.description() == null ? "" : tool.description(),
                    tool.callbackPath(),
                    tool.inputSchema()));
        }

        ClientRegistration registered = clientRegistry.register(
                clientId, clientType, payload.baseCallbackUrl(),
                payload.defaultCallbackPath(), payload.clientPrompt(), definitions);
        return new ToolsAckEnvelope("0.1", request.id(), "tools_ack",
                new ToolsAckPayload(registered.clientId(), registered.tools().size()));
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }

    /**
     * GET /clients
     *
     * <p>作用：查询已注册客户端列表，主要用于接入排障和后台查看。
     *
     * <p>入参：无。
     *
     * <p>返回 200 JSON 数组，每项含：
     * {@code clientId}、{@code clientType}、{@code baseCallbackUrl}、
     * {@code toolCount}、{@code tools}（工具名列表）、{@code status}。
     */
    @GetMapping
    public List<Map<String, Object>> listClients() {
        return clientRegistry.listClients().stream().map(client -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("clientId", client.clientId());
            item.put("clientType", client.clientType().name());
            item.put("baseCallbackUrl", client.baseCallbackUrl());
            item.put("toolCount", client.tools().size());
            item.put("tools", client.tools().stream().map(ToolDefinition::name).toList());
            item.put("status", client.status());
            return item;
        }).toList();
    }

    /**
     * GET /clients/{clientId}/tools
     *
     * <p>作用：查询某个客户端已注册的全部工具定义。
     *
     * <p>入参：路径参数 {@code clientId}。
     *
     * <p>返回 200：{@code ToolDefinition[]}，每项含
     * {@code name}、{@code description}、{@code callbackPath}、{@code inputSchema}。
     */
    @GetMapping("/{clientId}/tools")
    public List<ToolDefinition> listTools(@PathVariable String clientId) {
        return clientRegistry.listTools(clientId);
    }

    /**
     * GET /clients/{clientId}/tools/{name}
     *
     * <p>作用：查询某个客户端下的单个工具定义。
     *
     * <p>入参：路径参数 {@code clientId}、{@code name}。
     *
     * <p>返回：200 + {@code ToolDefinition}；不存在时 404。
     */
    @GetMapping("/{clientId}/tools/{name}")
    public ResponseEntity<ToolDefinition> getOne(@PathVariable String clientId, @PathVariable String name) {
        return ResponseEntity.of(clientRegistry.getTool(clientId, name));
    }

    /**
     * DELETE /clients/{clientId}/tools/{name}
     *
     * <p>作用：注销单个工具，客户端调整工具集时使用。
     *
     * <p>入参：路径参数 {@code clientId}、{@code name}。
     *
     * <p>返回 200：{@code clientId}、{@code name}、{@code removed}（是否确实删除）。
     */
    @DeleteMapping("/{clientId}/tools/{name}")
    public Map<String, Object> unregisterTool(@PathVariable String clientId, @PathVariable String name) {
        return Map.of("clientId", clientId, "name", name,
                "removed", clientRegistry.unregisterTool(clientId, name));
    }

    /**
     * DELETE /clients/{clientId}
     *
     * <p>作用：注销整个客户端（客户端下线/租户回收时使用）。
     *
     * <p>入参：路径参数 {@code clientId}。
     *
     * <p>返回 200：{@code clientId}、{@code removed}（删除的工具数量）。
     */
    @DeleteMapping("/{clientId}")
    public Map<String, Object> unregisterClient(@PathVariable String clientId) {
        return Map.of("clientId", clientId, "removed", clientRegistry.unregisterClient(clientId));
    }

    /**
     * 参数/回调地址校验失败统一返回 400 + error 信封，便于客户端快速定位接入问题。
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorEnvelope> handleBadRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(new ErrorEnvelope(
                "0.1", null, "error",
                new ErrorPayload("INVALID_REQUEST", e.getMessage())));
    }
}
