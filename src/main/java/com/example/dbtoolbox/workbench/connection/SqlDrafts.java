package com.example.dbtoolbox.workbench.connection;

import com.example.dbtoolbox.common.AppException;
import com.example.dbtoolbox.common.EncryptedJsonFileStore;
import com.example.dbtoolbox.common.StoragePaths;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;

import java.util.*;

/** Encrypted editor text only. All calls are guarded by ConnectionService's monitor. */
public final class SqlDrafts {
    static final int MAX_TABS = 20, MAX_SQL = 1024 * 1024, MAX_TOTAL = 8 * 1024 * 1024;
    private final EncryptedJsonFileStore<Catalog> store;

    SqlDrafts(StoragePaths paths, ObjectMapper mapper) {
        store = new EncryptedJsonFileStore<Catalog>(paths.configDir().resolve("sql-drafts.enc"), paths.keyFile(), mapper,
                new TypeReference<Catalog>() { }, Catalog::new);
    }

    public static class Draft {
        public String id, name, sql;
    }
    public static class Workspace {
        public String revision;
        public List<Draft> tabs = new ArrayList<Draft>();
    }
    public static class Update extends Workspace {
        public String requestId;
    }
    public static class Saved extends Workspace {
        public String requestId, previousRevision;
    }
    public static class Catalog {
        public int version = 1;
        public Map<String, Saved> connections = new LinkedHashMap<String, Saved>();
    }

    Workspace read(String id, String epoch) { return publicCopy(current(catalog(), id, epoch)); }

    Workspace save(String id, String epoch, Update request) {
        if (request == null || !uuid(request.requestId) || !uuid(request.revision))
            throw new AppException("草稿请求缺少有效版本或请求标识");
        validate(request.tabs);
        Catalog catalog = catalog();
        Saved previous = current(catalog, id, epoch);
        if (request.requestId.equals(previous.requestId)) {
            if (!request.revision.equals(previous.previousRevision) || !same(request.tabs, previous.tabs))
                throw new AppException(HttpStatus.CONFLICT, "同一草稿请求标识不能用于不同内容");
            return publicCopy(previous); // Safe retry after a lost response.
        }
        if (!request.revision.equals(previous.revision))
            throw new AppException(HttpStatus.CONFLICT, "草稿已被其他页面或连接设置更新。请先下载当前 SQL，再刷新页面；未覆盖已有草稿");
        Saved next = new Saved();
        next.revision = UUID.randomUUID().toString();
        next.previousRevision = previous.revision;
        next.requestId = request.requestId;
        next.tabs = copy(request.tabs);
        catalog.connections.put(id, next);
        long size = 0;
        for (Saved workspace : catalog.connections.values()) for (Draft draft : workspace.tabs) size += draft.sql.length();
        if (size > MAX_TOTAL) throw new AppException("已保存 SQL 草稿总量超过 8 Mi 字符，请下载文件并关闭部分草稿标签后重试");
        store.write(catalog);
        return publicCopy(next);
    }

    void clear(String id) {
        Catalog catalog = catalog();
        if (catalog.connections.remove(id) != null) store.write(catalog);
    }

    private Catalog catalog() {
        Catalog catalog = store.read();
        if (catalog == null || catalog.version != 1 || catalog.connections == null)
            throw new AppException("SQL 草稿格式无效，原文件已保留");
        for (Saved workspace : catalog.connections.values()) {
            if (workspace == null || !uuid(workspace.revision)) throw new AppException("SQL 草稿格式无效，原文件已保留");
            validate(workspace.tabs);
        }
        return catalog;
    }
    private static Saved current(Catalog catalog, String id, String epoch) {
        Saved found = catalog.connections.get(id);
        if (found != null) return found;
        Saved empty = new Saved();
        empty.revision = epoch;
        return empty;
    }
    private static void validate(List<Draft> tabs) {
        if (tabs == null || tabs.size() > MAX_TABS) throw new AppException("每个连接最多保存 20 个 SQL 草稿标签");
        Set<String> ids = new HashSet<String>();
        for (Draft tab : tabs) {
            if (tab == null || !uuid(tab.id) || !ids.add(tab.id) || tab.name == null || tab.name.length() > 200 || tab.sql == null)
                throw new AppException("SQL 草稿标签格式无效");
            if (tab.sql.length() > MAX_SQL) throw new AppException("单个 SQL 草稿超过 1 Mi 字符，请使用 SQL 文件保存");
        }
    }
    private static boolean uuid(String value) {
        return value != null && value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    }
    private static boolean same(List<Draft> a, List<Draft> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            Draft x = a.get(i), y = b.get(i);
            if (!x.id.equals(y.id) || !x.name.equals(y.name) || !x.sql.equals(y.sql)) return false;
        }
        return true;
    }
    private static List<Draft> copy(List<Draft> source) {
        List<Draft> result = new ArrayList<Draft>();
        for (Draft tab : source) {
            Draft next = new Draft(); next.id = tab.id; next.name = tab.name; next.sql = tab.sql; result.add(next);
        }
        return result;
    }
    private static Workspace publicCopy(Saved source) {
        Workspace result = new Workspace(); result.revision = source.revision; result.tabs = copy(source.tabs); return result;
    }
}
