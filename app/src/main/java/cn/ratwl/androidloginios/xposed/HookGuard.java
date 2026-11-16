package cn.ratwl.androidloginios.xposed;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Hook 回调的「保险丝」。
 * <p>
 * 面具（LSPosed）的 XposedBridge 在分发回调时会自己 try/catch 掉 Throwable，
 * 回调里抛异常最多是日志里多一行；但免 ROOT 容器（元萝卜 / SlimVXposed / SHook）
 * 的分发实现并不保证这一点——某些版本会把回调里的异常直接抛给宿主调用方，
 * 于是「模块自己出错」就变成了「目标应用闪退」。
 * <p>
 * 所以这里做三件事：
 * 1) 所有回调体统一走 {@link #safe}，异常一律在模块内部消化，绝不外泄给宿主；
 * 2) 用 ThreadLocal 做重入保护，避免「hook 反射 API 时又触发反射」导致的无限递归；
 * 3) 连续失败达到阈值就自动停用全部 Hook，宁可功能失效也不拖垮宿主。
 */
public final class HookGuard {

    private static final int MAX_FAILURES = 15;

    private static final ThreadLocal<Boolean> BUSY = new ThreadLocal<>();
    private static final AtomicInteger FAILURES = new AtomicInteger();

    private static volatile boolean disabled;

    private HookGuard() {
    }

    public interface Body {
        void run() throws Throwable;
    }

    public static void safe(String where, Body body) {
        if (disabled) {
            return;
        }
        if (Boolean.TRUE.equals(BUSY.get())) {
            // 已经在我们的回调里了，再进就是递归，直接放行原方法
            return;
        }
        BUSY.set(Boolean.TRUE);
        try {
            body.run();
        } catch (Throwable t) {
            int count = FAILURES.incrementAndGet();
            if (count <= 8) {
                HookLogger.log("[" + where + "] 已吞掉异常: " + t);
            }
            if (count >= MAX_FAILURES) {
                disabled = true;
                HookLogger.log("Hook 失败 " + count + " 次，已自动停用（宿主不再受影响）");
            }
        } finally {
            BUSY.set(Boolean.FALSE);
        }
    }

    public static boolean isDisabled() {
        return disabled;
    }
}
