package com.agent.demo.clientagent.client_tool;

/**
 * 客户端类型，决定工具调用的传输方式。
 * <ul>
 *   <li>{@link #SERVER}：服务端客户端，有公网可达地址，工具走 HTTP 回调</li>
 *   <li>{@link #MOBILE}：移动端客户端，无公网地址，工具走 WebSocket 推送</li>
 * </ul>
 */
public enum ClientType {

    /** 服务端客户端：有公网可达地址，工具走 HTTP 回调 */
    SERVER,

    /** 移动端客户端：无公网地址，工具走 WebSocket 推送 */
    MOBILE
}
