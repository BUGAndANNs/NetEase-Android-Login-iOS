package cn.ratwl.androidloginios.bridge;

import android.content.ContentProviderClient;
import android.content.Context;
import android.content.Intent;
import android.content.UriPermission;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.provider.DocumentsContract;

import java.io.IOException;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 准备文件的双通道写入与限定清理。
 *
 * <p>通道一（默认）：{@code content://<包名>.widget_file_provider/widget_file_cache/<随机名>}，
 * 对应真实路径 {@code <dataDir>/cache/<随机名>}，适用于光·遇等开放缓存接口的游戏。</p>
 *
 * <p>通道二（SAF）：经系统目录授权写入 {@code Android/media/<包名>}，
 * 适用于阴阳师等不含 widget_file_provider 的游戏。</p>
 *
 * <p>清理范围严格限定在自身生成的 {@code ntes_mpay_<32位hex>.tmp} 文件，
 * 绝不扫描或删除游戏自身文件。</p>
 */
public final class SeedStorage {
    private static final String AUTHORITY = "com.android.externalstorage.documents";
    private static final String MEDIA_ID = "primary:Android/media";
    private static final Pattern FILE_NAME = Pattern.compile("^ntes_mpay_[a-f0-9]{32}\\.tmp$");
    private static final Pattern CACHE_URI =
            Pattern.compile("^content://[a-zA-Z0-9._-]+/widget_file_cache/ntes_mpay_[a-f0-9]{32}\\.tmp$");

    private SeedStorage() {
    }

    public static SeedTransportMode getTransportMode(Context context, String packageName) {
        String raw = BridgePrefs.get(context)
                .getString(BridgePrefs.seedModeKey(packageName), null);
        return SeedTransportMode.fromPref(raw);
    }

    public static void setTransportMode(Context context, String packageName, SeedTransportMode mode) {
        BridgePrefs.get(context).edit()
                .putString(BridgePrefs.seedModeKey(packageName), mode.prefValue)
                .apply();
    }

    public static boolean hasValidDirectoryGrant(Context context, String packageName) {
        if (!BridgeProtocol.isValidPackageName(packageName)) {
            return false;
        }
        String saved = BridgePrefs.get(context)
                .getString(BridgePrefs.mediaTreeKey(packageName), null);
        if (saved == null) {
            return false;
        }
        Uri tree;
        try {
            tree = Uri.parse(saved);
        } catch (Throwable t) {
            return false;
        }
        try {
            requireTree(tree, packageName);
        } catch (Throwable t) {
            return false;
        }
        return hasPersistedPermission(context, tree);
    }

    /**
     * 拉起网页容器前先确认通道可用，避免先开一个空 WebView 再报错。
     */
    public static void ensureReady(Context context, GameTarget target, SeedTransportMode mode)
            throws SeedStorageException, DirectoryAccessRequired {
        if (mode == SeedTransportMode.STANDARD) {
            if (!canReachProvider(context, target.cacheUri("probe"))) {
                throw new SeedStorageException(
                        "无法连接「" + target.appLabel + "」缓存接口；若该游戏未开放缓存接口（如阴阳师），"
                                + "请切换为「共享目录方式」后重试。");
            }
            return;
        }

        String pkg = target.spec.packageName;
        String saved = BridgePrefs.get(context).getString(BridgePrefs.mediaTreeKey(pkg), null);
        if (saved == null) {
            throw new DirectoryAccessRequired();
        }
        Uri tree;
        try {
            tree = Uri.parse(saved);
        } catch (Throwable t) {
            throw new DirectoryAccessRequired();
        }
        try {
            requireTree(tree, pkg);
        } catch (Throwable t) {
            throw new DirectoryAccessRequired();
        }
        if (!hasPersistedPermission(context, tree)) {
            throw new DirectoryAccessRequired();
        }

        String folderId = MEDIA_ID + "/" + pkg;
        Uri folder = DocumentsContract.buildDocumentUriUsingTree(tree, folderId);
        boolean folderExists = false;
        try {
            android.database.Cursor c = context.getContentResolver()
                    .query(folder, new String[]{DocumentsContract.Document.COLUMN_MIME_TYPE},
                            null, null, null);
            if (c != null) {
                try {
                    folderExists = c.moveToFirst()
                            && DocumentsContract.Document.MIME_TYPE_DIR.equals(c.getString(0));
                } finally {
                    c.close();
                }
            }
        } catch (Throwable ignored) {
            folderExists = false;
        }
        if (!folderExists && !MEDIA_ID.equals(DocumentsContract.getTreeDocumentId(tree))) {
            throw new DirectoryAccessRequired();
        }

        resolvePrimaryStorageRoot(context);
    }

    public static Intent directoryIntent() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI,
                    DocumentsContract.buildDocumentUri(AUTHORITY, MEDIA_ID));
        } catch (Throwable ignored) {
            // 少数 ROM 不支持初始目录
        }
        return intent;
    }

    public static void saveDirectoryGrant(Context context, String packageName, Uri uri, int flags)
            throws SeedStorageException {
        requireTree(uri, packageName);
        int required = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
        int access = flags & required;
        if (access != required) {
            throw new SeedStorageException("目录未授予读写权限，请重新选择。");
        }
        try {
            context.getContentResolver().takePersistableUriPermission(uri, access);
        } catch (SecurityException e) {
            throw new SeedStorageException("系统未允许保存目录授权，请重新选择目录。");
        }
        BridgePrefs.get(context).edit()
                .putString(BridgePrefs.mediaTreeKey(packageName), uri.toString())
                .apply();
    }

    public static PreparedSeed write(Context context, GameTarget target, String name,
                                     byte[] bytes, SeedTransportMode mode)
            throws SeedStorageException, DirectoryAccessRequired {
        if (!FILE_NAME.matcher(name).matches()) {
            throw new SeedStorageException("非法的准备文件名。");
        }

        if (mode == SeedTransportMode.STANDARD) {
            Uri cacheUri = target.cacheUri(name);
            remember(context, cacheUri, true);
            try {
                writeBytes(context, cacheUri, bytes);
                return new PreparedSeed(target.localCacheFilePath(name), cacheUri, false);
            } catch (Throwable t) {
                delete(context, cacheUri.toString());
                throw new SeedStorageException(
                        "无法写入「" + target.appLabel + "」缓存接口；若该游戏未开放缓存接口（如阴阳师），"
                                + "请切换为「共享目录方式」后重试。");
            }
        }

        String pkg = target.spec.packageName;
        String saved = BridgePrefs.get(context).getString(BridgePrefs.mediaTreeKey(pkg), null);
        if (saved == null) {
            throw new DirectoryAccessRequired();
        }
        Uri tree = Uri.parse(saved);
        try {
            requireTree(tree, pkg);
            if (!hasPersistedPermission(context, tree)) {
                throw new DirectoryAccessRequired();
            }
            return writeMedia(context, pkg, tree, name, bytes);
        } catch (DirectoryAccessRequired e) {
            throw e;
        } catch (SecurityException e) {
            throw new DirectoryAccessRequired();
        } catch (SeedStorageException e) {
            throw e;
        } catch (Throwable t) {
            throw new SeedStorageException("无法写入已授权的游戏媒体目录，请检查目录是否仍存在且空间充足。");
        }
    }

    private static PreparedSeed writeMedia(Context context, String pkg, Uri tree,
                                           String name, byte[] bytes)
            throws SeedStorageException, DirectoryAccessRequired, IOException {
        String folderId = MEDIA_ID + "/" + pkg;
        Uri folder = DocumentsContract.buildDocumentUriUsingTree(tree, folderId);

        boolean folderExists = false;
        try {
            android.database.Cursor c = context.getContentResolver()
                    .query(folder, new String[]{DocumentsContract.Document.COLUMN_MIME_TYPE},
                            null, null, null);
            if (c != null) {
                try {
                    folderExists = c.moveToFirst()
                            && DocumentsContract.Document.MIME_TYPE_DIR.equals(c.getString(0));
                } finally {
                    c.close();
                }
            }
        } catch (Throwable ignored) {
            folderExists = false;
        }

        if (!folderExists) {
            if (!MEDIA_ID.equals(DocumentsContract.getTreeDocumentId(tree))) {
                throw new DirectoryAccessRequired();
            }
            Uri root = DocumentsContract.buildDocumentUriUsingTree(tree, MEDIA_ID);
            Uri created = DocumentsContract.createDocument(
                    context.getContentResolver(), root,
                    DocumentsContract.Document.MIME_TYPE_DIR, pkg);
            if (created == null || !folderId.equals(DocumentsContract.getDocumentId(created))) {
                throw new SeedStorageException("未能创建游戏媒体目录。");
            }
            folder = created;
        }

        Uri file = DocumentsContract.createDocument(context.getContentResolver(), folder,
                "application/octet-stream", name);
        if (file == null) {
            throw new SeedStorageException("未能创建临时准备文件。");
        }
        if (!(folderId + "/" + name).equals(DocumentsContract.getDocumentId(file))) {
            // 只删除本次 createDocument 返回的 URI，绝不扫描游戏文件
            try {
                DocumentsContract.deleteDocument(context.getContentResolver(), file);
            } catch (Throwable ignored) {
                // ignore
            }
            throw new SeedStorageException("系统返回的文件名与预期路径不一致。");
        }

        remember(context, file, true);
        try {
            writeBytes(context, file, bytes);
        } catch (Throwable t) {
            delete(context, file.toString());
            throw new SeedStorageException("写入准备文件失败，请检查目录是否可写。");
        }

        String primaryRoot = resolvePrimaryStorageRoot(context);
        return new PreparedSeed(primaryRoot + "/Android/media/" + pkg + "/" + name, file, true);
    }

    private static String resolvePrimaryStorageRoot(Context context) throws SeedStorageException {
        if (!Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) {
            throw new SeedStorageException("主共享存储当前不可用。");
        }
        String volumeDir = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                StorageManager sm = (StorageManager) context.getSystemService(Context.STORAGE_SERVICE);
                if (sm != null && sm.getPrimaryStorageVolume() != null
                        && sm.getPrimaryStorageVolume().getDirectory() != null) {
                    volumeDir = sm.getPrimaryStorageVolume().getDirectory().getAbsolutePath();
                }
            } catch (Throwable ignored) {
                volumeDir = null;
            }
        }
        String root = volumeDir;
        if (root == null || root.isEmpty()) {
            try {
                java.io.File external = Environment.getExternalStorageDirectory();
                root = external == null ? null : external.getAbsolutePath();
            } catch (Throwable ignored) {
                root = null;
            }
        }
        if (root == null) {
            throw new SeedStorageException("无法解析当前用户主存储路径。");
        }
        while (root.endsWith("/")) {
            root = root.substring(0, root.length() - 1);
        }
        if (root.isEmpty()) {
            throw new SeedStorageException("无法解析当前用户主存储路径。");
        }
        return root;
    }

    private static void writeBytes(Context context, Uri uri, byte[] bytes) throws IOException {
        OutputStream out = context.getContentResolver().openOutputStream(uri, "wt");
        if (out == null) {
            throw new IOException("stream-unavailable");
        }
        try {
            out.write(bytes);
            out.flush();
        } finally {
            try {
                out.close();
            } catch (Throwable ignored) {
                // ignore
            }
        }
    }

    private static void requireTree(Uri uri, String pkg) throws SeedStorageException {
        if (!BridgeProtocol.isValidPackageName(pkg)
                || uri == null
                || !"content".equals(uri.getScheme())
                || !AUTHORITY.equals(uri.getAuthority())
                || !DocumentsContract.isTreeUri(uri)) {
            throw new SeedStorageException("请选择内部存储的 Android/media 目录或该游戏的子目录。");
        }
        String id = DocumentsContract.getTreeDocumentId(uri);
        if (!MEDIA_ID.equals(id) && !(MEDIA_ID + "/" + pkg).equals(id)) {
            throw new SeedStorageException("请选择内部存储的 Android/media 目录或 " + pkg + " 子目录。");
        }
    }

    private static boolean hasPersistedPermission(Context context, Uri tree) {
        try {
            List<UriPermission> permissions = context.getContentResolver().getPersistedUriPermissions();
            if (permissions == null) {
                return false;
            }
            for (UriPermission permission : permissions) {
                if (tree.equals(permission.getUri())
                        && permission.isReadPermission()
                        && permission.isWritePermission()) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            return false;
        }
        return false;
    }

    private static boolean canReachProvider(Context context, Uri uri) {
        ContentProviderClient client = null;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                client = context.getContentResolver().acquireUnstableContentProviderClient(uri);
            } else {
                client = context.getContentResolver().acquireContentProviderClient(uri);
            }
            return client != null;
        } catch (Throwable t) {
            return false;
        } finally {
            if (client != null) {
                try {
                    client.close();
                } catch (Throwable ignored) {
                    // ignore
                }
            }
        }
    }

    /** 清理上次遗留的准备文件（启动/回到前台时调用）。 */
    public static void cleanup(Context context) {
        try {
            Set<String> entries = BridgePrefs.get(context)
                    .getStringSet(BridgePrefs.KEY_TEMP_URIS, null);
            if (entries == null) {
                return;
            }
            for (String entry : new HashSet<>(entries)) {
                delete(context, entry);
            }
        } catch (Throwable ignored) {
            // 清理失败不影响主流程
        }
    }

    /** 只接受本模块随机命名的临时文件，避免误删游戏文件。 */
    private static boolean isSafeMediaUri(Uri uri) {
        try {
            if (uri == null
                    || !"content".equals(uri.getScheme())
                    || !AUTHORITY.equals(uri.getAuthority())
                    || !DocumentsContract.isTreeUri(uri)) {
                return false;
            }
            String id = DocumentsContract.getDocumentId(uri);
            if (id == null || !id.startsWith(MEDIA_ID + "/")) {
                return false;
            }
            String rest = id.substring(MEDIA_ID.length() + 1);
            String[] parts = rest.split("/");
            if (parts.length != 2
                    || !BridgeProtocol.isValidPackageName(parts[0])
                    || !FILE_NAME.matcher(parts[1]).matches()) {
                return false;
            }
            requireTree(uri, parts[0]);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static void delete(Context context, String value) {
        if (value == null) {
            return;
        }
        Uri uri;
        try {
            uri = Uri.parse(value);
        } catch (Throwable t) {
            return;
        }
        boolean media = isSafeMediaUri(uri);
        if (!media && !CACHE_URI.matcher(value).matches()) {
            return;
        }
        try {
            if (media) {
                DocumentsContract.deleteDocument(context.getContentResolver(), uri);
            } else {
                context.getContentResolver().delete(uri, null, null);
            }
            remember(context, uri, false);
        } catch (Throwable ignored) {
            // ignore
        }
    }

    public static void deleteAsync(final Context context, final String value) {
        if (value == null) {
            return;
        }
        final Context appContext = context.getApplicationContext();
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                delete(appContext, value);
            }
        }, "NeteaseSeedCleanup");
        thread.setDaemon(true);
        thread.start();
    }

    private static synchronized void remember(Context context, Uri uri, boolean add) {
        try {
            android.content.SharedPreferences prefs = BridgePrefs.get(context);
            Set<String> entries = prefs.getStringSet(BridgePrefs.KEY_TEMP_URIS, null);
            HashSet<String> next = entries == null ? new HashSet<String>() : new HashSet<>(entries);
            if (add) {
                next.add(uri.toString());
            } else {
                next.remove(uri.toString());
            }
            prefs.edit().putStringSet(BridgePrefs.KEY_TEMP_URIS, next).apply();
        } catch (Throwable ignored) {
            // ignore
        }
    }
}
