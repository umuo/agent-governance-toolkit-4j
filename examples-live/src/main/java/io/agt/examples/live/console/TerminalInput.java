package io.agt.examples.live.console;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 唯一 stdin 读取者，避免审批超时后的读取线程抢走下一条用户消息。
 *
 * <p>主对话和审批只消费队列；后台线程为 daemon，EOF 后重复返回 null。
 */
public final class TerminalInput {
    private record Line(String text, IOException error) {}

    private final LinkedBlockingQueue<Line> lines = new LinkedBlockingQueue<>(128);
    private boolean ended;

    public TerminalInput(BufferedReader reader) {
        var thread =
                new Thread(
                        () -> {
                            try {
                                String text;
                                while ((text = reader.readLine()) != null) {
                                    lines.put(new Line(text, null));
                                }
                                lines.put(new Line(null, null));
                            } catch (IOException error) {
                                try {
                                    lines.put(new Line(null, error));
                                } catch (InterruptedException interrupted) {
                                    Thread.currentThread().interrupt();
                                }
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                            }
                        },
                        "agt-console-input");
        thread.setDaemon(true);
        thread.start();
    }

    public synchronized String readLine() throws IOException, InterruptedException {
        return ended ? null : value(lines.take());
    }

    public synchronized String readLine(long timeoutMillis)
            throws IOException, InterruptedException {
        return ended ? null : value(lines.poll(Math.max(1, timeoutMillis), TimeUnit.MILLISECONDS));
    }

    private String value(Line line) throws IOException {
        if (line == null) {
            return null;
        }
        if (line.text() == null) {
            ended = true;
        }
        if (line.error() != null) {
            throw line.error();
        }
        return line.text();
    }
}
