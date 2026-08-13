package com.yxh.sapvendorportal.common.utils;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** 管理员本地生成 BCrypt 密码哈希的命令行工具；只输出哈希，不会联网或保存密码。 */
public final class PasswordHashTool {
    private PasswordHashTool() { }
    public static void main(String[] args) {
        if (args.length != 1 || args[0].isBlank()) throw new IllegalArgumentException("请传入一个待加密密码。");
        System.out.println(new BCryptPasswordEncoder(12).encode(args[0]));
    }
}
