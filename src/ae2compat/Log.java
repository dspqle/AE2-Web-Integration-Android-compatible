package ae2compat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * 早期阶段日志。
 *
 * ModLauncher 的 System.out 在这个环境里可能被 launcher 重定向/吞掉，
 * 因此这里除了 stdout 之外，还会把内容同时写进游戏目录下的 
 * logs/ae2androidcompat.log，确保任何阶段都能看到输出。
 */
public final class Log {

    private static final String PREFIX = "[AE2AndroidCompat] ";
    private static volatile File sink;
    private static volatile boolean sinkResolved = false;

    private Log() {
    }

    private static synchronized File sink() {
        if (sinkResolved) {
            return sink;
        }
        sinkResolved = true;
        try {
            String explicit = System.getProperty("ae2compat.log.file");
            if (explicit != null && !explicit.isEmpty()) {
                sink = new File(explicit);
                return sink;
            }
            String gd = System.getProperty("fml.gameDirectory");
            if (gd == null) {
                gd = System.getProperty("user.dir");
            }
            File dir = new File(gd == null ? "." : gd, "logs");
            if (!dir.isDirectory() && !dir.mkdirs()) {
                dir = new File(gd == null ? "." : gd);
            }
            sink = new File(dir, "ae2androidcompat.log");
            try (PrintWriter pw = new PrintWriter(new FileOutputStream(sink, false))) {
                pw.println("# ae2androidcompat early log");
            }
        } catch (Throwable t) {
            sink = null;
        }
        return sink;
    }

    private static void write(String line) {
        System.out.println(line);
        System.out.flush();
        File f = sink();
        if (f == null) {
            return;
        }
        try (PrintWriter pw = new PrintWriter(new FileOutputStream(f, true))) {
            pw.println(line);
        } catch (Throwable ignored) {
            // 日志失败绝不阻断加载
        }
    }

    public static void info(String msg) {
        write(PREFIX + msg);
    }

    public static void warn(String msg) {
        write(PREFIX + "[WARN] " + msg);
    }

    public static void throwable(String msg, Throwable t) {
        StringWriter sw = new StringWriter();
        sw.append(PREFIX).append(msg).append(' ').append(String.valueOf(t));
        if (t != null) {
            StringWriter st = new StringWriter();
            PrintWriter pw = new PrintWriter(st);
            t.printStackTrace(pw);
            pw.flush();
            sw.append('\n').append(st.toString());
        }
        write(sw.toString());
    }
}
