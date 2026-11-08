package ae2compat;

import java.util.Collections;

import cpw.mods.modlauncher.api.ITransformer;
import net.neoforged.neoforgespi.coremod.ICoreMod;

/**
 * Android/bionic 兼容 CoreMod（v2）。
 *
 * 只做一件事：在 FML 的 CoreMod 阶段（早于任何 mod 类加载）
 * 触发与 TransformationService 相同的 setup()：
 *   释放内嵌 libsqlitejdbc.so -> 设置 org.sqlite.lib.path。
 * 两个入口互为双保险，setup() 内部幂等，重复调用无副作用。
 */
public final class AE2AndroidCompatCoreMod implements ICoreMod {

    static {
        Log.info("CoreMod 类已加载(static init) classloader="
                + AE2AndroidCompatCoreMod.class.getClassLoader());
    }

    public AE2AndroidCompatCoreMod() {
        Log.info("CoreMod 实例已构造");
    }

    @Override
    public Iterable<? extends ITransformer<?>> getTransformers() {
        Log.info("CoreMod.getTransformers() 被调用（v2：不再注册整类替换）");
        AE2AndroidCompatTransformationService.runSetup();
        return Collections.<ITransformer<?>>emptyList();
    }
}
