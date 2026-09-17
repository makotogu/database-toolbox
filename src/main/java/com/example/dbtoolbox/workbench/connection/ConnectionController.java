package com.example.dbtoolbox.workbench.connection;

import com.example.dbtoolbox.common.ApiResponse;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/connections")
public class ConnectionController {
    private final ConnectionService connections;
    public ConnectionController(ConnectionService connections) { this.connections = connections; }

    @GetMapping public ApiResponse<List<ConnectionProfile>> list() { return ApiResponse.ok(connections.list()); }
    @PostMapping public ApiResponse<ConnectionProfile> save(@RequestBody ConnectionProfile request) { return ApiResponse.ok(connections.save(request)); }
    @PostMapping("/test") public ApiResponse<Map<String, Object>> test(@RequestBody ConnectionProfile request) { return ApiResponse.ok(connections.test(request)); }
    @DeleteMapping("/{id}") public ApiResponse<Void> delete(@PathVariable String id) {
        connections.delete(id);
        return ApiResponse.ok(null);
    }
    @GetMapping("/legacy") public ApiResponse<Map<String, Object>> legacy() { return ApiResponse.ok(connections.migrationStatus()); }
    @GetMapping("/{id}/sql-drafts") public ApiResponse<SqlDrafts.Workspace> drafts(@PathVariable String id) {
        return ApiResponse.ok(connections.sqlDrafts(id));
    }
    @PutMapping("/{id}/sql-drafts") public ApiResponse<SqlDrafts.Workspace> saveDrafts(@PathVariable String id, @RequestBody SqlDrafts.Update request) {
        return ApiResponse.ok(connections.saveSqlDrafts(id, request));
    }
}
