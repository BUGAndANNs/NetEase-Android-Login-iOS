package cn.ratwl.androidloginios.bridge;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 单次会话的本地回环 MPay iOS 登录桥。
 *
 * <p>流程：拉起游戏的 GMWebviewActivity 加载本地页面 → 页面读取当前登录账号（UID / game_id）
 * → 写出不含登录凭据（token 用占位符 {@code "0"}）的 iOS 会话种子并调用 {@code loadMpaySession}
 * → 用户回游戏点「切换账号」重登同一账号即进入 iOS 角色。</p>
 *
 * <p><b>为什么 token 是 {@code "0"} 而不是空串</b>：对齐上游 netease-ios v1.3——较新的 MPay SDK
 * 会拒绝空 token 的会话种子，占位符 {@code "0"} 可以通过校验；它同样不是真实凭据。</p>
 *
 * <p>全程不接触密码、验证码或真实 token；本地服务仅绑定 127.0.0.1，端口随机，
 * 路径带一次性 24 字节 nonce，并校验 Host / Origin，45 秒后自动销毁。</p>
 */
public final class MpayBridgeSession implements AutoCloseable {

    public interface Listener {
        void onSubmitted(String gameId, boolean confirmed, boolean usedSaf);

        void onFailed(String message);
    }

    private static final String THREAD_SERVER = "NeteaseLocalBridge";
    private static final long TIMEOUT_MS = 45_000L;
    private static final long ACTIVITY_DELAY_MS = 1_500L;
    private static final long SEED_CLEANUP_DELAY_MS = 45_000L;

    private final Context appContext;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean resultDelivered = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final ServerSocket server;
    private final String nonce;
    private final String base;
    private final String host;
    private final GameTarget target;
    private final SeedTransportMode transportMode;
    private final Listener listener;
    private final String expectedGameId;

    private volatile String capturedGameId = "";
    private volatile PreparedSeed preparedSeed;
    private volatile boolean unconfirmedGraceDelete = false;

    public MpayBridgeSession(Context context, GameTarget target,
                             SeedTransportMode transportMode, Listener listener) throws Exception {
        this.appContext = context.getApplicationContext();
        this.target = target;
        this.transportMode = transportMode;
        this.listener = listener;
        this.expectedGameId = target.spec.gameId;

        SeedStorage.ensureReady(appContext, target, transportMode);

        byte[] random = new byte[24];
        new SecureRandom().nextBytes(random);
        StringBuilder hex = new StringBuilder(48);
        for (byte b : random) {
            hex.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        }
        this.nonce = hex.toString();
        this.base = "/bridge/" + nonce;

        ServerSocket socket = new ServerSocket();
        socket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
        socket.setSoTimeout(1000);
        this.server = socket;
        this.host = "127.0.0.1:" + socket.getLocalPort();
    }

    public void start() {
        BridgePrefs.get(appContext).edit()
                .putString(BridgePrefs.KEY_STATE, "preparing")
                .putString(BridgePrefs.KEY_STATE_PACKAGE, target.spec.packageName)
                .remove(BridgePrefs.KEY_LAST_ERROR)
                .putString(BridgePrefs.KEY_OPERATION, nonce)
                .remove(BridgePrefs.KEY_COMPLETED_OPERATION)
                .apply();

        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                serve();
            }
        }, THREAD_SERVER);
        thread.setDaemon(true);
        thread.start();

        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (closed.get()) {
                    return;
                }
                if (!resultDelivered.get()) {
                    fail("未能完成 iOS 登录准备，请先确保已在「" + target.appLabel
                            + "」正常登录账号后再重试。");
                }
                close();
            }
        }, TIMEOUT_MS);

        // 上一个容器结束后再开新的，避免残留的静态 WebView 状态
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (closed.get()) {
                    return;
                }
                Intent intent = new Intent();
                intent.setComponent(new ComponentName(target.spec.packageName, target.gmActivityClass));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                intent.putExtra("GM_WEBVIEW_URL", "http://" + host + base + "/page");
                try {
                    appContext.startActivity(intent);
                } catch (Throwable t) {
                    fail("无法拉起「" + target.appLabel
                            + "」网页容器，该游戏大概率不支持此登录方式"
                            + "（仅支持开放网页桥接口的网易官服游戏）。");
                    close();
                }
            }
        }, ACTIVITY_DELAY_MS);
    }

    private void serve() {
        while (!closed.get()) {
            try (Socket socket = server.accept()) {
                if (!socket.getInetAddress().isLoopbackAddress()) {
                    continue;
                }
                socket.setSoTimeout(2500);

                HttpRequest request = HttpRequest.read(socket.getInputStream());
                boolean correctHost = host.equals(request.headers.get("host"));
                String origin = request.headers.get("origin");
                if (!correctHost
                        || (origin != null && !("http://" + host).equals(origin))
                        || !request.path.startsWith(base + "/")) {
                    reply(socket, 403, "text/plain", "Unavailable");
                    continue;
                }

                String route = request.path.substring(base.length());
                if ("GET".equals(request.method) && "/page".equals(route)) {
                    reply(socket, 200, "text/html; charset=utf-8",
                            BridgePage.render(target.appLabel, "http://" + host + base));
                    continue;
                }
                if (!"POST".equals(request.method)) {
                    reply(socket, 404, "text/plain", "Unavailable");
                    continue;
                }

                JSONObject input;
                try {
                    input = new JSONObject(new String(request.body, StandardCharsets.UTF_8));
                } finally {
                    java.util.Arrays.fill(request.body, (byte) 0);
                }

                switch (route) {
                    case "/prepare":
                        handlePrepare(socket, input);
                        break;
                    case "/submitted":
                        handleSubmitted(socket, input);
                        break;
                    case "/failed":
                        handleFailed(socket, input);
                        break;
                    default:
                        reply(socket, 404, "text/plain", "Unavailable");
                        break;
                }
            } catch (SocketTimeoutException ignored) {
                // 正常的 accept 超时，继续轮询
            } catch (Throwable ignored) {
                // 单次请求出错不影响后续
            }
        }
    }

    private void handlePrepare(Socket socket, JSONObject input) throws IOException {
        ContextIdentity identity = null;
        try {
            identity = readIdentity(input);
        } catch (Throwable ignored) {
            identity = null;
        }
        if (identity == null) {
            fail("游戏客户端返回的上下文格式不匹配，请重新打开「" + target.appLabel + "」后再试。");
            reply(socket, 200, "application/json", "{\"ok\":false,\"reason\":\"source_mismatch\"}");
            return;
        }
        if (identity.uid == null) {
            fail("未读取到已登录的网易账号，请先在「" + target.appLabel + "」正常登录该账号后再试。");
            reply(socket, 200, "application/json", "{\"ok\":false,\"reason\":\"not_logged_in\"}");
            return;
        }

        capturedGameId = identity.gameId;
        BridgePrefs.get(appContext).edit()
                .putString(BridgePrefs.lastGameIdKey(target.spec.packageName), identity.gameId)
                .apply();

        PreparedSeed seed;
        try {
            seed = getOrWriteSeed(identity.uid);
        } catch (SeedStorageException e) {
            fail(e.getMessage() == null ? "准备文件写入失败，请检查目录授权或写入方式后重试。" : e.getMessage());
            reply(socket, 200, "application/json", "{\"ok\":false,\"reason\":\"storage_failed\"}");
            return;
        } catch (DirectoryAccessRequired e) {
            fail("共享媒体目录授权已失效，请返回助手重新授权 Android/media 目录。");
            reply(socket, 200, "application/json", "{\"ok\":false,\"reason\":\"directory_required\"}");
            return;
        } catch (Throwable t) {
            fail("准备文件写入失败，请返回助手检查写入方式与剩余空间后重试。");
            reply(socket, 200, "application/json", "{\"ok\":false,\"reason\":\"storage_failed\"}");
            return;
        }

        JSONObject reqData = new JSONObject();
        JSONObject command = new JSONObject();
        try {
            reqData.put("channel", BridgeProtocol.DEFAULT_LOGIN_CHANNEL);
            reqData.put("methodId", "loadMpaySession");
            reqData.put("path", seed.sdkPath);
            reqData.put("callback_mode", "web");
            command.put("methodId", "execute_extend_func");
            command.put("reqData", reqData);
        } catch (Throwable t) {
            fail("构造登录准备指令失败，请重试。");
            reply(socket, 200, "application/json", "{\"ok\":false,\"reason\":\"prepare_failed\"}");
            return;
        }

        JSONObject response = new JSONObject();
        try {
            response.put("ok", true);
            response.put("command", command);
        } catch (Throwable ignored) {
            // JSONObject.put 理论上不会抛
        }
        reply(socket, 200, "application/json", response.toString());
    }

    private void handleSubmitted(Socket socket, JSONObject input) throws IOException {
        if (preparedSeed == null) {
            reply(socket, 403, "application/json", "{\"ok\":false}");
            return;
        }
        boolean confirmed = input.optBoolean("confirmed", false);
        boolean usedSaf = preparedSeed.usedSaf;
        String gameId = capturedGameId;
        boolean first = resultDelivered.compareAndSet(false, true);
        if (first) {
            unconfirmedGraceDelete = !confirmed;
            BridgePrefs.get(appContext).edit()
                    .putString(BridgePrefs.KEY_STATE, confirmed ? "submitted" : "unconfirmed")
                    .putString(BridgePrefs.KEY_STATE_PACKAGE, target.spec.packageName)
                    .putString(BridgePrefs.KEY_COMPLETED_OPERATION, nonce)
                    .apply();
        }
        reply(socket, 200, "application/json", "{\"ok\":true}");
        if (first) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    listener.onSubmitted(gameId, confirmed, usedSaf);
                }
            });
        }
    }

    private void handleFailed(Socket socket, JSONObject input) throws IOException {
        String reason = input.optString("reason");
        String message;
        switch (reason) {
            case "not_logged_in":
                message = "未读取到已登录的网易账号，请先在「" + target.appLabel + "」正常登录该账号后再试。";
                break;
            case "no_callback":
                message = "没有收到游戏接口响应，请返回游戏关闭当前页面后重试。";
                break;
            case "bridge_missing":
                message = "游戏网页桥未就绪，请先打开「" + target.appLabel + "」登录进入主页后再试。";
                break;
            case "sdk_read_failed":
                message = "SDK 未能读取准备文件，该游戏可能不支持当前写入方式。";
                break;
            case "sdk_invalid_session":
                message = "SDK 已读取文件，但未接受账号准备格式，当前游戏仍需适配。";
                break;
            case "sdk_rejected":
                message = "SDK 拒绝了本次登录准备，当前游戏仍需适配。";
                break;
            default:
                message = "本次准备未完成，请返回游戏关闭当前页面后重试。";
                break;
        }
        reply(socket, 200, "application/json", "{\"ok\":true}");
        fail(message);
    }

    private ContextIdentity readIdentity(JSONObject input) throws IOException {
        String game = input.optString("game");
        if (!BridgeProtocol.supportedSource(game, expectedGameId)) {
            throw new IOException("source");
        }
        String full = input.optString("full");
        if (full == null || full.trim().isEmpty()) {
            return new ContextIdentity(null, game);
        }
        return new ContextIdentity(BridgeProtocol.uidFromQualifiedName(full), game);
    }

    private synchronized PreparedSeed getOrWriteSeed(String uid) throws Exception {
        PreparedSeed cached = preparedSeed;
        if (cached != null) {
            return cached;
        }

        String fileName = "ntes_mpay_" + UUID.randomUUID().toString().replace("-", "") + ".tmp";
        byte[] bytes = new byte[0];
        try {
            JSONObject extInfo = new JSONObject();
            extInfo.put("src_client_type", BridgeProtocol.IOS_CLIENT_TYPE);
            extInfo.put("is_remember", true);
            extInfo.put("is_from_ticket_login", true);
            JSONObject user = new JSONObject();
            user.put("id", uid);
            user.put("token", "0");
            user.put("need_mask", true);
            user.put("login_type", BridgeProtocol.DEFAULT_LOGIN_TYPE);
            user.put("login_channel", BridgeProtocol.DEFAULT_LOGIN_CHANNEL);
            user.put("pc_ext_info", extInfo);
            JSONObject root = new JSONObject();
            root.put("user", user);
            bytes = (root.toString() + "\n").getBytes(StandardCharsets.UTF_8);
        } catch (Throwable t) {
            throw new SeedStorageException("构造准备文件失败。");
        }

        PreparedSeed seed;
        try {
            seed = SeedStorage.write(appContext, target, fileName, bytes, transportMode);
        } finally {
            java.util.Arrays.fill(bytes, (byte) 0);
        }
        preparedSeed = seed;

        final String cleanupUri = seed.cleanupUri.toString();
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                SeedStorage.deleteAsync(appContext, cleanupUri);
            }
        }, SEED_CLEANUP_DELAY_MS);
        return seed;
    }

    private void fail(String message) {
        Log.w("NetEaseMPayMod", "bridge: " + message);
        if (!resultDelivered.compareAndSet(false, true)) {
            return;
        }
        BridgePrefs.get(appContext).edit()
                .putString(BridgePrefs.KEY_STATE, "failed")
                .putString(BridgePrefs.KEY_STATE_PACKAGE, target.spec.packageName)
                .putString(BridgePrefs.KEY_LAST_ERROR, message)
                .remove(BridgePrefs.KEY_COMPLETED_OPERATION)
                .apply();
        main.post(new Runnable() {
            @Override
            public void run() {
                listener.onFailed(message);
            }
        });
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            server.close();
        } catch (Throwable ignored) {
            // ignore
        }
        PreparedSeed seed = preparedSeed;
        if (seed != null) {
            final String cleanupUri = seed.cleanupUri.toString();
            if (unconfirmedGraceDelete) {
                // 未收到 SDK 确认时留 5 秒，让 SDK 有机会把文件读完
                main.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        SeedStorage.deleteAsync(appContext, cleanupUri);
                    }
                }, 5_000L);
            } else {
                SeedStorage.deleteAsync(appContext, cleanupUri);
            }
        }
    }

    public String nonce() {
        return nonce;
    }

    /** 启动时清理历史上残留的临时文件。 */
    public static void cleanup(Context context) {
        final Context appContext = context.getApplicationContext();
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                SeedStorage.cleanup(appContext);
            }
        }, "NeteaseSeedBootCleanup");
        thread.setDaemon(true);
        thread.start();
    }

    public static SharedPreferences prefs(Context context) {
        return BridgePrefs.get(context);
    }

    private static void reply(Socket socket, int status, String type, String text) throws IOException {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        String statusText = status == 200 ? "OK" : "Unavailable";
        String header = "HTTP/1.1 " + status + " " + statusText + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n"
                + "Content-Security-Policy: default-src 'none';connect-src 'self';"
                + "script-src 'unsafe-inline';style-src 'unsafe-inline'\r\n\r\n";
        java.io.OutputStream out = socket.getOutputStream();
        out.write(header.getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    private static final class ContextIdentity {
        final String uid;
        final String gameId;

        ContextIdentity(String uid, String gameId) {
            this.uid = uid;
            this.gameId = gameId;
        }
    }
}
