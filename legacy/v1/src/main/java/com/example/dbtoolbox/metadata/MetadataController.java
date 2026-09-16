package com.example.dbtoolbox.metadata;

import com.example.dbtoolbox.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/datasources/{datasourceId}")
public class MetadataController {

    private final MetadataService metadataService;

    public MetadataController(MetadataService metadataService) {
        this.metadataService = metadataService;
    }

    @GetMapping("/tables")
    public ApiResponse<List<TableInfo>> listTables(@PathVariable String datasourceId,
                                                   @RequestParam(required = false) String schema) {
        return ApiResponse.ok(metadataService.listTables(datasourceId, schema));
    }

    @GetMapping("/columns")
    public ApiResponse<List<ColumnInfo>> listColumns(@PathVariable String datasourceId,
                                                     @RequestParam(required = false) String schema,
                                                     @RequestParam String table) {
        return ApiResponse.ok(metadataService.listColumns(datasourceId, schema, table));
    }
}
