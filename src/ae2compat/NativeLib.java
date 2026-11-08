package ae2compat;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * 原生库读取与释放。
 *
 * 关键约束（Android）：
 *   - /storage/emulated/0 等 sdcardfs/FUSE 路径通常挂载为 noexec，
 *     放在这里的 .so 无法被 dlopen（System.load 会抛 UnsatisfiedLinkError）。
 *     因此必须释放到进程私有且可执行的目录，例如 java.io.tmpdir。
 *   - FCL 传给 JVM 的 java.io.tmpdir 位于 app 私有目录内，可写可执行。
 */
public final class NativeLib {

    private static volatile File selfJar;
    private static volatile boolean jarResolved;

    private NativeLib() {
    }

    /** 定位自身 jar（含 native/ 条目）。多路兜底。 */
    private static synchronized File selfJar() {
        if (jarResolved) {
            return selfJar;
        }
        jarResolved = true;

        List<File> candidates = new ArrayList<File>();

        // 路 1：本类自身 URL（jar:file:/...!/ae2compat/NativeLib.class）
        try {
            URL u = NativeLib.class.getResource("NativeLib.class");
            Log.info("探针 getResource(NativeLib.class) = " + u);
            File f = jarFromUrl(u);
            if (f != null) {
                candidates.add(f);
            }
        } catch (Throwable t) {
            Log.warn("路1 异常: " + t);
        }

        // 路 2：CodeSource
        try {
            java.security.CodeSource cs = NativeLib.class.getProtectionDomain().getCodeSource();
            Log.info("探针 CodeSource = " + (cs == null ? "null" : cs.getLocation()));
            if (cs != null && cs.getLocation() != null) {
                File f = jarFromUrl(cs.getLocation());
                if (f != null) {
                    candidates.add(f);
                }
            }
        } catch (Throwable t) {
            Log.warn("路2 异常: " + t);
        }

        // 路 3：classloader 的 getResource（可能返回原始 jar URL）
        try {
            ClassLoader cl = NativeLib.class.getClassLoader();
            URL u = cl == null ? null : cl.getResource("ae2compat/NativeLib.class");
            Log.info("探针 classloader.getResource = " + u);
            File f = jarFromUrl(u);
            if (f != null) {
                candidates.add(f);
            }
        } catch (Throwable t) {
            Log.warn("路3 异常: " + t);
        }

        // 路 4：扫描 java.class.path 与常见 mods 目录
        for (File f : scanDisk()) {
            candidates.add(f);
        }

        for (File f : candidates) {
            if (f != null && f.isFile()) {
                try (JarFile jf = new JarFile(f)) {
                    if (jf.getEntry("native/libsqlitejdbc.so") != null) {
                        Log.info("自身 jar = " + f.getAbsolutePath());
                        selfJar = f;
                        return selfJar;
                    }
                } catch (Throwable t) {
                    // ignore
                }
            }
        }
        Log.warn("自身 jar 定位失败，候选数=" + candidates.size());
        return null;
    }

    private static File jarFromUrl(URL u) {
        if (u == null) {
            return null;
        }
        String s = u.toString();
        try {
            if (s.startsWith("jar:")) {
                int bang = s.indexOf("!/");
                String p = bang > 4 ? s.substring(4, bang) : s.substring(4);
                return new File(new java.net.URI(p));
            }
            if (s.startsWith("file:")) {
                File f = new File(new java.net.URI(s));
                // 若是目录形式（class 直接在目录里），尝试向上回溯找 jar
                if (f.isFile()) {
                    return f;
                }
            }
        } catch (Throwable t) {
            // ignore
        }
        return null;
    }

    /** 扫描 classpath 与游戏目录下的 mods 目录中匹配的 jar。 */
    private static List<File> scanDisk() {
        List<File> out = new ArrayList<File>();
        // classpath
        String cp = System.getProperty("java.class.path", "");
        Log.info("探针 java.class.path = " + cp);
        for (String part : cp.split(File.pathSeparator)) {
            if (part.isEmpty()) {
                continue;
            }
            File f = new File(part);
            String n = f.getName().toLowerCase(java.util.Locale.ROOT);
            if (n.contains("ae2androidcompat") && n.endsWith(".jar")) {
                out.add(f);
            }
        }
        // 游戏目录 mods
        for (String key : new String[] { "fml.gameDirectory", "user.dir" }) {
            String gd = System.getProperty(key);
            if (gd == null) {
                continue;
            }
            File mods = new File(gd, "mods");
            File[] list = mods.listFiles();
            if (list == null) {
                continue;
            }
            for (File f : list) {
                String n = f.getName().toLowerCase(java.util.Locale.ROOT);
                if (n.contains("ae2androidcompat") && n.endsWith(".jar")) {
                    out.add(f);
                }
            }
        }
        return out;
    }

    /** 读取 jar 内条目为字节数组；找不到返回 null。 */
    public static byte[] read(String entry) {
        // 优先：直接从 classloader 读资源流（不解 jar 路径，最稳）
        try {
            InputStream in = NativeLib.class.getClassLoader() == null
                    ? ClassLoader.getSystemResourceAsStream(entry)
                    : NativeLib.class.getClassLoader().getResourceAsStream(entry);
            if (in != null) {
                Log.info("从 classloader 资源流读取: " + entry);
                try {
                    return drain(in);
                } finally {
                    in.close();
                }
            }
        } catch (Throwable t) {
            Log.warn("classloader 资源读取失败: " + t);
        }

        // 兜底：定位 jar 后按条目读
        File jar = selfJar();
        if (jar == null) {
            return null;
        }
        try (JarFile jf = new JarFile(jar)) {
            JarEntry je = jf.getJarEntry(entry);
            if (je == null) {
                return null;
            }
            try (InputStream in = jf.getInputStream(je)) {
                return drain(in);
            }
        } catch (Throwable t) {
            Log.warn("JarFile 读取失败: " + t);
            return null;
        }
    }

    private static byte[] drain(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(2 * 1024 * 1024);
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    /**
     * 把字节写出到进程可写、且可执行映射的目录，返回该目录绝对路径。
     * Android 上 sdcard 是 noexec，必须用 java.io.tmpdir（app 私有）。
     */
    public static String extractToWritableDir(byte[] data, String fileName) {
        List<File> dirs = new ArrayList<File>();

        String tmp = System.getProperty("java.io.tmpdir");
        Log.info("探针 java.io.tmpdir = " + tmp);
        if (tmp != null && !tmp.isEmpty()) {
            dirs.add(new File(tmp, "ae2compat-native"));
        }
        String uh = System.getProperty("user.home");
        if (uh != null && !uh.isEmpty()) {
            dirs.add(new File(uh, ".ae2compat-native"));
        }
        // 兜底：游戏目录下的 .ae2compat-native（可能 noexec，但总比失败好）
        String gd = System.getProperty("fml.gameDirectory");
        if (gd == null) {
            gd = System.getProperty("user.dir");
        }
        if (gd != null) {
            dirs.add(new File(gd, ".ae2compat-native"));
        }

        for (File d : dirs) {
            try {
                if (!d.isDirectory() && !d.mkdirs()) {
                    Log.info("无法创建目录: " + d);
                    continue;
                }
                File target = new File(d, fileName);
                if (target.isFile() && target.length() == data.length) {
                    Log.info("复用已存在的原生库: " + target.getAbsolutePath());
                    return d.getAbsolutePath();
                }
                try (OutputStream out = new FileOutputStream(target)) {
                    out.write(data);
                }
                target.setReadable(true, false);
                target.setExecutable(true, false);
                Log.info("写出原生库: " + target.getAbsolutePath()
                        + " (" + target.length() + " bytes)");
                return d.getAbsolutePath();
            } catch (Throwable t) {
                Log.warn("写出失败 " + d + " : " + t);
            }
        }
        return null;
    }
}
