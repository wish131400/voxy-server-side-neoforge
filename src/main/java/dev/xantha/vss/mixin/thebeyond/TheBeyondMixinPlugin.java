package dev.xantha.vss.mixin.thebeyond;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Set;
import net.neoforged.fml.loading.LoadingModList;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.service.MixinService;

/**
 * The Beyond merges a handler into StructureStart.placeInChunk whose End check calls level.getLevel(), which VSS's
 * prediction levels cannot answer, so every predicted structure failed there. Mixin calls postApply only after every
 * mixin of the target finished all passes, so the handler is already merged when {@link BeyondHandlerPatch} looks for
 * it. Nothing here loads a game class: the decision reads class bytes as a resource, the patch works on the ClassNode
 * only, and FML's mod list is read for the log text.
 */
public final class TheBeyondMixinPlugin implements IMixinConfigPlugin {
    static final String TARGET = BeyondHandlerPatch.TARGET.replace('/', '.');

    @Override public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return BeyondHandlerPatch.GUARD_MIXIN.equals(mixinClassName) && TARGET.equals(targetClassName) && beyondInstalled();
    }

    /** The Beyond's handler mixin is present as class bytes; Mixin reads every mod's configuration through the same service. */
    static boolean beyondInstalled() {
        try (InputStream mixin = MixinService.getService().getResourceAsStream(BeyondHandlerPatch.BEYOND_MIXIN.replace('.', '/') + ".class")) {
            return mixin != null;
        } catch (IOException | RuntimeException | LinkageError unreadable) {
            return false;
        }
    }

    @Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo info) {
        if (!BeyondHandlerPatch.GUARD_MIXIN.equals(mixinClassName)) return;
        Report report = report(targetClass);
        // Mixin's own logger: plugin code stays within JDK, FML, Mixin and ASM classes.
        var log = MixinService.getService().getLogger("VSS");
        if (report.error()) log.error(report.message());
        else log.info(report.message());
    }

    /** The patch and its one log line; a refusal is an ERROR that names The Beyond, and nothing is thrown to Mixin. */
    static Report report(ClassNode targetClass) {
        BeyondHandlerPatch.Result result;
        try {
            result = BeyondHandlerPatch.apply(targetClass);
        } catch (RuntimeException | LinkageError failure) {
            // Every check precedes the single insertion, so a failure here has changed nothing.
            return new Report(true, "Left The Beyond's (the_beyond) StructureStart.placeInChunk handler untouched: inspection failed ("
                    + failure + "; installed the_beyond " + hostVersion("the_beyond") + "). Predicted structures keep failing on The Beyond's unguarded level.getLevel().");
        }
        if (result.patched())
            return new Report(false, "Guarded The Beyond's StructureStart.placeInChunk handler " + result.handler() + ": " + result.detail() + ".");
        return new Report(true, "Left The Beyond's (the_beyond) StructureStart.placeInChunk handler untouched (" + result.outcome() + "): "
                + result.detail() + ". Predicted structures keep failing on The Beyond's unguarded level.getLevel(); installed the_beyond "
                + hostVersion("the_beyond") + ".");
    }

    record Report(boolean error, String message) { }

    /** Log text only: the installed version of a host, or a placeholder when FML's mod list is not available. */
    static String hostVersion(String modId) {
        try {
            LoadingModList mods = LoadingModList.get();
            if (mods != null) for (var mod : mods.getMods()) if (mod.getModId().equals(modId)) return mod.getVersion().toString();
        } catch (RuntimeException | LinkageError unavailable) {
            // The version is only part of the log text.
        }
        return "(version unavailable)";
    }

    @Override public void onLoad(String mixinPackage) { }
    @Override public String getRefMapperConfig() { return null; }
    @Override public void acceptTargets(Set<String> mine, Set<String> others) { }
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo info) { }
}
