package io.agt.examples.asi02;

import io.agt.examples.support.ToolScenario;
import java.sql.DriverManager;
import java.util.Set;

/**
 * 参数租户合法不代表 SQL 正确做了行级授权；参数化 SQL 也不会修复遗漏的租户条件。
 *
 * <p>直接运行 main；公共接线在 ToolScenario，策略和工具行为在本类。
 */
public final class SqlBusinessAuthorizationDemo {
    public static void main(String[] args) throws Exception {
        // H2 仅内存数据库，数据全是合成值；不连接宿主数据库。
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:asi02;DB_CLOSE_DELAY=0")) {
            try (var statement = connection.createStatement()) {
                statement.execute(
                        "CREATE TABLE docs(id INT PRIMARY KEY, tenant VARCHAR, body VARCHAR)");
                statement.execute(
                        "INSERT INTO docs VALUES(2, 'tenant-b', 'synthetic-other-tenant')");
            }
            ToolScenario.run(
                    "SQL 缺少行级授权",
                    ToolScenario.allowed(false).build(),
                    ToolScenario.identity(Set.of("read")),
                    ToolScenario.VALID,
                    null,
                    values -> {
                        // 故意保留业务漏洞：用了 PreparedStatement，但忽略已校验的 tenant。
                        try (var sql =
                                connection.prepareStatement("SELECT body FROM docs WHERE id=?")) {
                            sql.setInt(1, 2);
                            try (var rows = sql.executeQuery()) {
                                rows.next();
                                return rows.getString(1);
                            }
                        }
                    },
                    null,
                    "synthetic-other-tenant",
                    1);
            ToolScenario.run(
                    "SQL 增加租户条件",
                    ToolScenario.allowed(false).build(),
                    ToolScenario.identity(Set.of("read")),
                    ToolScenario.VALID,
                    null,
                    values -> {
                        try (var sql =
                                connection.prepareStatement(
                                        "SELECT body FROM docs WHERE id=? AND tenant=?")) {
                            sql.setInt(1, 2);
                            sql.setString(2, (String) values.get("tenant"));
                            try (var rows = sql.executeQuery()) {
                                return rows.next() ? rows.getString(1) : "not-found";
                            }
                        }
                    },
                    null,
                    "not-found",
                    1);
            System.out.println("AGT 许可检查与数据库行级授权必须同时成立。");
        }
    }
}
