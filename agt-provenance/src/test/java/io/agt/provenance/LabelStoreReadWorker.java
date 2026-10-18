package io.agt.provenance;

import io.agt.core.JsonValues;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.h2.jdbcx.JdbcDataSource;

/** 独立 JVM 直接读取已关闭的文件目录，输出仅含固定测试元数据，不输出 JDBC URL。 */
public final class LabelStoreReadWorker {
    public static void main(String[] ignored) throws Exception {
        var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        var config = JsonValues.parseObject(input.readLine());
        var source = new JdbcDataSource();
        source.setURL((String) config.get("jdbc"));
        var labels = new JdbcLabelStore(source, 2);
        var mark = labels.read(JdbcLabelStoreTest.MEMORY).orElseThrow();
        var event = labels.history(JdbcLabelStoreTest.MEMORY, mark.revision()).orElseThrow();
        System.out.println(
                mark.label()
                        + ":"
                        + mark.revision()
                        + ":"
                        + event.provenance().taskId()
                        + ":"
                        + event.provenance().kind());
    }
}
