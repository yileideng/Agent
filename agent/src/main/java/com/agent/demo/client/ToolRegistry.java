package com.agent.demo.client;

import java.util.HashMap;
import java.util.Map;

public class ToolRegistry {
    private static Map<String, ClientTool> clientToolMap = new HashMap<>();

    // 查询所有客户端工具
    public static Map<String, ClientTool> getClientToolMap() {
        return clientToolMap;
    }

    // 添加客户端工具
    public static void registerClientTool(ClientTool clientTool) {
        clientToolMap.put(clientTool.getName(), clientTool);
    }
}
