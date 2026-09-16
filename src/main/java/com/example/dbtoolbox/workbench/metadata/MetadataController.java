package com.example.dbtoolbox.workbench.metadata;

import com.example.dbtoolbox.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/connections/{id}")
public class MetadataController {
    private final MetadataService metadata;
    public MetadataController(MetadataService metadata) { this.metadata = metadata; }

    @GetMapping("/objects")
    public ApiResponse<List<Map<String, Object>>> objects(@PathVariable String id, @RequestParam String kind,
            @RequestParam(required = false) String catalog, @RequestParam(required = false) String schema) {
        return ApiResponse.ok(metadata.objects(id, kind, catalog, schema));
    }

    @GetMapping("/table-structure")
    public ApiResponse<Map<String, Object>> structure(@PathVariable String id, @RequestParam(required = false) String catalog,
            @RequestParam(required = false) String schema, @RequestParam String table) {
        return ApiResponse.ok(metadata.tableStructure(id, catalog, schema, table));
    }

    @GetMapping("/routine-detail")
    public ApiResponse<Map<String, Object>> routine(@PathVariable String id, @RequestParam(required = false) String catalog,
            @RequestParam(required = false) String schema, @RequestParam String name,
            @RequestParam(defaultValue = "PROCEDURE") String type, @RequestParam(required = false) String specificName) {
        return ApiResponse.ok(metadata.routineDetail(id, catalog, schema, name, type, specificName));
    }
}
