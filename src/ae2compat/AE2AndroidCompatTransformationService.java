package ae2compat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import cpw.mods.modlauncher.api.IEnvironment;
import cpw.mods.modlauncher.api.ITransformationService;
import cpw.mods.modlauncher.api.ITransformer;
import cpw.mods.modlauncher.api.IncompatibleEnvironmentException;

/**
 * AE2 Web Integration / sqlite-jdbc 的 Android(bionic) 兼容服务。
 *
 * 方案变更说明（v2）：
 *   旧方案用 ITransformer 整类替换 org.sqlite.SQLiteJDBCLoader，实测无效。
 *   原因：sqlite-jdbc 经 JarJar 提升为 PLUGIN 层的模块 org.xerial.sqlitejdbc，
 *   其类由该层自己的 ModuleClassLoader 加载，不经过 ModLauncher 的
 *   TransformingClassLoader，因此 ClassTransformer.transform() 永远不会被调用。
 *   （日志证据：org.sqlite.* 栈帧的 audit trail 标记为 '{}'，而其它类都有
 *    're:classloading'。）
 *
 *   新方案完全不改字节码，只利用 sqlite-jdbc 官方支持的扩展点：
 *   SQLiteJDBCLoader.loadSQLiteNativeLibrary() 第一步就读系统属性
 *   "org.sqlite.lib.path"，若该目录下存在 libsqlitejdbc.so 则直接
 *   System.load(绝对路径) 并返回，不再走 java.library.path / jar 内提取。
 *   因此本服务只需：
 *     1) 把打进本 jar 的 bionic 版 libsqlitejdbc.so 释放到进程可写目录；
 *     2) 设置 System.setProperty("org.sqlite.lib.path", <该目录>)。
 *   时间点必须早于 SQLiteJDBCLoader 类初始化，transformers()/onLoad() 阶段
 *   远早于进入世界，完全安全。
 */
public final class AE2AndroidCompatTransformationService implements ITransformationService {

    static {
        Log.info("TransformationService 类已加载(static init) classloader="
                + AE2AndroidCompatTransformationService.class.getClassLoader());
    }

    public AE2AndroidCompatTransformationService() {
        Log.info("TransformationService 实例已构造");
    }

    @Override
    public String name() {
        return "ae2androidcompat";
    }

    @Override
    public void initialize(IEnvironment environment) {
        Log.info("initialize() 被调用");
        setup();
    }

    @Override
    public void onLoad(IEnvironment env, Set<String> otherServices)
            throws IncompatibleEnvironmentException {
        Log.info("onLoad() 被调用，已存在其它服务数=" + otherServices.size());
        setup();
    }

    @Override
    public List<ITransformer<?>> transformers() {
        Log.info("transformers() 被调用（v2：不再注册整类替换）");
        setup();
        return new ArrayList<ITransformer<?>>();
    }

    /** 幂等：释放原生库并设置 org.sqlite.lib.path。 */
    private static volatile boolean done;

    static synchronized void runSetup() {
        setup();
    }

    private static synchronized void setup() {
        if (done) {
            return;
        }
        try {
            Log.info("平台: os.name=" + System.getProperty("os.name")
                    + ", os.arch=" + System.getProperty("os.arch"));

            // 如果用户/其它组件已经指定了可用目录，尊重它。
            String preset = System.getProperty("org.sqlite.lib.path");
            if (preset != null && new File(preset, "libsqlitejdbc.so").isFile()) {
                Log.info("org.sqlite.lib.path 已存在且可用，跳过: " + preset);
                done = true;
                return;
            }

            byte[] so = NativeLib.read("native/libsqlitejdbc.so");
            if (so == null) {
                Log.warn("jar 内未找到 native/libsqlitejdbc.so");
                return;
            }
            Log.info("已读取原生库字节数=" + so.length);

            String dir = NativeLib.extractToWritableDir(so, "libsqlitejdbc.so");
            if (dir == null) {
                Log.warn("释放原生库失败：无可写目录");
                return;
            }
            Log.info("原生库已释放到: " + dir);
            System.setProperty("org.sqlite.lib.path", dir);
            Log.info("已设置 org.sqlite.lib.path=" + System.getProperty("org.sqlite.lib.path"));
            done = true;
        } catch (Throwable t) {
            Log.warn("setup 异常: " + t);
            Log.throwable("setup 异常", t);
        }
    }
}
