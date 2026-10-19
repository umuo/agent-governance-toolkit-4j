package io.agt.tools.jdbc;

/** 有界管理目录的摘要；读取仍须认证与访问审计，不可用于绕过实际版本检查。 */
public record ToolReleaseSummary(
        String binding, String name, String provider, String version, String status) {}
