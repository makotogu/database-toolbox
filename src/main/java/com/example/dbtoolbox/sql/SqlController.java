package com.example.dbtoolbox.sql;

import com.example.dbtoolbox.common.ApiResponse;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;

@Validated
@RestController
@RequestMapping("/api/sql")
public class SqlController {

    private final SqlService sqlService;

    public SqlController(SqlService sqlService) {
        this.sqlService = sqlService;
    }

    @PostMapping("/execute")
    public ApiResponse<SqlExecutionResult> execute(@Valid @RequestBody SqlExecutionRequest request) {
        return ApiResponse.ok(sqlService.execute(request));
    }
}
