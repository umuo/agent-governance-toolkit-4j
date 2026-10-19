package io.agt.tools.jdbc;

import io.agt.core.tools.ToolDescriptor;
import java.util.List;

/** 已授权管理端读取的发布历史投影；不包含查看 secret、JWT 或制品字节，不授予批准能力。 */
public record ToolReleaseInfo(
        ToolDescriptor descriptor, String publisher, String status, List<Event> events) {
    public ToolReleaseInfo {
        events = List.copyOf(events);
    }

    public record Event(String kind, String actor, long epochMillis, String reference) {}

    @Override
    public String toString() {
        return "ToolReleaseInfo[redacted]";
    }
}
