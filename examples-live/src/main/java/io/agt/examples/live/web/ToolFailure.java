package io.agt.examples.live.web;

import io.agt.core.GovernanceException;
import java.io.IOException;
import java.nio.file.*;
import java.util.Map;

/** 只向网页发布已知错误的固定说明，避免泄漏底层异常中的文件内容或私密路径。 */
record ToolFailure(String code, String message, String hint) {
    private static final Map<String, String> MESSAGES =
            Map.ofEntries(
                    Map.entry(
                            "workspace_path_denied", "路径被拒绝：文件工具只允许工作区内的相对路径。绝对路径和越界的 ../ 路径不可用。"),
                    Map.entry("workspace_link_denied", "路径被拒绝：文件工具不允许访问符号链接。"),
                    Map.entry("file_not_found", "文件或父目录不存在。"),
                    Map.entry("file_access_denied", "操作系统拒绝访问该文件或目录。"),
                    Map.entry("invalid_path", "路径格式无效。"),
                    Map.entry("regular_file_required", "目标必须是普通文件，不能是目录或其他特殊文件。"),
                    Map.entry("file_size_limit", "文件超过工具的 32768 字节限制。"),
                    Map.entry("invalid_utf8", "文件不是有效的 UTF-8 文本。"),
                    Map.entry("edit_requires_unique_match", "编辑失败：oldText 必须非空，并且在文件中恰好出现一次。"),
                    Map.entry("empty_search_denied", "搜索内容不能为空。"),
                    Map.entry("file_io_failed", "文件读写失败；具体底层异常请查看服务端日志。"),
                    Map.entry("sandbox_exit_denied", "Bash 命令执行失败（非零退出）。非零退出不等同于权限拒绝。"),
                    Map.entry("sandbox_unavailable", "当前环境无法启动所配置的原生沙箱；严格读取模式下命令未执行，也不会自动开放宿主读取。"),
                    Map.entry("sandbox_policy_unsupported", "当前原生沙箱不支持所配置的隔离策略。"),
                    Map.entry("sandbox_timeout", "Bash 执行超时。"),
                    Map.entry("sandbox_cancelled", "Bash 执行已取消。"),
                    Map.entry("sandbox_cleanup_unconfirmed", "沙箱回收状态无法确认，该后端已停止接受新任务。"),
                    Map.entry("sandbox_start_failed", "沙箱目标启动失败。"),
                    Map.entry("sandbox_output_failed", "沙箱输出捕获失败，结果未交付。"),
                    Map.entry("sandbox_request_invalid", "沙箱请求不符合宿主策略。"),
                    Map.entry("sandbox_output_limit", "Bash 输出超过限制。"),
                    Map.entry("tool_failed", "工具执行失败；未识别的底层异常已记录在服务端日志。"),
                    Map.entry("session_failed", "本轮会话已因先前错误终止，后续工具未执行。"));

    static ToolFailure from(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            String code = null;
            for (Throwable diagnostic : cause.getSuppressed()) {
                if (diagnostic instanceof io.agt.examples.live.tools.BashTool.CommandFailed bash) {
                    return new ToolFailure(
                            "bash_command_failed",
                            "Bash 命令执行失败，退出码：" + bash.exitCode() + "。非零退出不等同于权限拒绝。",
                            bashHint());
                }
            }
            if (cause instanceof GovernanceException g) code = g.code();
            else if (cause instanceof NoSuchFileException) code = "file_not_found";
            else if (cause instanceof AccessDeniedException || cause instanceof SecurityException)
                code = "file_access_denied";
            else if (cause instanceof InvalidPathException) code = "invalid_path";
            else if (cause instanceof java.nio.charset.CharacterCodingException)
                code = "invalid_utf8";
            else if (cause instanceof IOException
                    && cause.getMessage() != null
                    && MESSAGES.containsKey(cause.getMessage())) code = cause.getMessage();
            if (code != null) return of(code);
        }
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof IOException) return of("file_io_failed");
        }
        return of("tool_failed");
    }

    boolean recoverableFileError() {
        return switch (code) {
            case "file_not_found",
                            "invalid_path",
                            "regular_file_required",
                            "file_size_limit",
                            "invalid_utf8",
                            "edit_requires_unique_match",
                            "empty_search_denied" ->
                    true;
            default -> false;
        };
    }

    String result() {
        return io.agt.core.JsonValues.write(
                Map.of("code", code, "message", message, "hint", hint, "recoverable", true));
    }

    private static String bashHint() {
        return "当前 Bash 网络策略为 DENY，禁止访问外网及本机网络服务；curl 等联网命令无法正常访问网络，人工审批不会开放网络。"
                + "这是已配置的限制，不代表所有命令失败均由网络导致；本地命令仍需排查参数、路径和系统权限。"
                + "退出码为命令自身定义，不能脱离命令解读；原始 stderr 未作为工具结果交付。";
    }

    static ToolFailure of(String code) {
        String hint =
                switch (code) {
                    case "workspace_path_denied", "workspace_link_denied" ->
                            "请使用工作区内的相对路径，例如 notes.txt；人工批准 Bash 不会扩大文件工具的路径范围。";
                    case "file_not_found" ->
                            "使用 listFiles(\".\") 查看工作区根目录；其他路径均相对于工作区，不要猜测 workspace 子目录。";
                    case "file_access_denied" -> "检查运行后端的系统用户对目标文件及父目录的权限。";
                    case "sandbox_exit_denied", "bash_command_failed" -> bashHint();
                    default -> "可通过本轮 runId 对照服务端日志与审计记录。";
                };
        return new ToolFailure(code, MESSAGES.getOrDefault(code, "治理操作被拒绝（" + code + "）。"), hint);
    }
}
