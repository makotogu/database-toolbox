package com.example.dbtoolbox.workbench.driver;

import com.example.dbtoolbox.common.ApiResponse;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/drivers")
public class DriverController {
    private final DriverService drivers;
    public DriverController(DriverService drivers) { this.drivers = drivers; }

    @GetMapping public ApiResponse<List<DriverProfile>> list() { return ApiResponse.ok(drivers.list()); }

    @PostMapping("/import")
    public ApiResponse<DriverProfile> importFiles(@RequestParam("files") MultipartFile[] files,
                                                 @RequestParam(required = false) String name,
                                                 @RequestParam(required = false) String driverClass) {
        return ApiResponse.ok(drivers.importFiles(files, name, driverClass));
    }

    @PostMapping("/{id}/class")
    public ApiResponse<DriverProfile> select(@PathVariable String id, @RequestBody Map<String, String> body) {
        return ApiResponse.ok(drivers.selectClass(id, body.get("driverClass")));
    }

    @DeleteMapping("/{id}") public ApiResponse<Void> delete(@PathVariable String id) {
        drivers.delete(id);
        return ApiResponse.ok(null);
    }
}
