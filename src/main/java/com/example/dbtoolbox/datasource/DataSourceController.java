package com.example.dbtoolbox.datasource;

import com.example.dbtoolbox.common.ApiResponse;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;

@Validated
@RestController
@RequestMapping("/api/datasources")
public class DataSourceController {

    private final DataSourceService dataSourceService;

    public DataSourceController(DataSourceService dataSourceService) {
        this.dataSourceService = dataSourceService;
    }

    @GetMapping
    public ApiResponse<List<DataSourceView>> list() {
        return ApiResponse.ok(dataSourceService.list());
    }

    @PostMapping
    public ApiResponse<DataSourceView> save(@Valid @RequestBody DataSourceRequest request) {
        return ApiResponse.ok(dataSourceService.save(request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable String id) {
        dataSourceService.delete(id);
        return ApiResponse.ok(null);
    }

    @PostMapping("/{id}/test")
    public ApiResponse<DataSourceTestResult> test(@PathVariable String id) {
        return ApiResponse.ok(dataSourceService.test(id));
    }

    @PostMapping("/test")
    public ApiResponse<DataSourceTestResult> testRequest(@Valid @RequestBody DataSourceRequest request) {
        return ApiResponse.ok(dataSourceService.testRequest(request));
    }
}
