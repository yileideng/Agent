package com.agent.demo.agentsdk;

/**
 * 客户端接入网关的传输方式。
 *
 * <p>两种方式对客户端开发者是同一套 API：同一份工具定义、同一个 ToolHandler、同一个 chat；
 * 差别只在底层是"长连接"还是"HTTP 回调"。
 */
public enum Transport {

    /** 客户端主动出站建立 WebSocket，适合 PC / App / NAT 后的客户端。 */
    WEBSOCKET,

    /** 客户端暴露 HTTP 回调端点，适合有公网/同网地址的服务端客户端。 */
    HTTP_CALLBACK
}
