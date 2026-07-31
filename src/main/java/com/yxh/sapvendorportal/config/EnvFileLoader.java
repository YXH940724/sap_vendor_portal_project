package com.yxh.sapvendorportal.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** 仅用于本地开发；部署环境应使用密钥管理或容器环境变量。 */
public final class EnvFileLoader {
    private EnvFileLoader() { }

    public static void load() {
        Path envFile = Path.of(".env");
        if (!Files.isRegularFile(envFile)) return;
        try (var lines = Files.lines(envFile)) {
            lines.map(String::trim)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#") && line.contains("="))
                    .forEach(EnvFileLoader::loadLine);
        } catch (IOException exception) {
            throw new IllegalStateException("无法读取 .env 配置文件。", exception);
        }
    }

    private static void loadLine(String line) {
        int delimiter = line.indexOf('=');
        String key = line.substring(0, delimiter).trim();
        String value = line.substring(delimiter + 1).trim();
        if ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'"))) {
            value = value.substring(1, value.length() - 1);
        }
        if (!key.isBlank() && System.getenv(key) == null && System.getProperty(key) == null) System.setProperty(key, value);
    }
}
