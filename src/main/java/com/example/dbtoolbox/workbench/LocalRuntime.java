package com.example.dbtoolbox.workbench;

import com.example.dbtoolbox.common.ApiResponse;
import com.example.dbtoolbox.common.StoragePaths;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.context.event.EventListener;
import org.springframework.boot.web.servlet.context.ServletWebServerInitializedEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.filter.OncePerRequestFilter;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class LocalRuntime extends OncePerRequestFilter {
    private final StoragePaths paths;
    private final ObjectMapper mapper;
    private final String token;
    private FileChannel lockChannel;
    private FileLock lock;
    public LocalRuntime(StoragePaths paths, ObjectMapper mapper) {
        this.paths = paths; this.mapper = mapper;
        byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes);
        this.token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    @PostConstruct public synchronized void initialize() {
        if (lock != null && lock.isValid()) return;
        try {
        Files.createDirectories(paths.root());
        lockChannel = FileChannel.open(paths.root().resolve(".workbench.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try { lock = lockChannel.tryLock(); } catch (RuntimeException ex) { lockChannel.close(); throw ex; }
        if (lock == null) { lockChannel.close(); throw new IllegalStateException("数据目录正由另一个工作台使用: " + paths.root()); }
        System.out.println("数据库工作台 V2 · 数据目录: " + paths.root());
        } catch(IOException ex) { throw new IllegalStateException("无法锁定本地数据目录: " + paths.root(), ex); }
    }
    @PreDestroy public synchronized void closeLock() {
        try { if (lock != null && lock.isValid()) lock.release(); }
        catch(IOException ignored) { }
        try { if (lockChannel != null) lockChannel.close(); }
        catch(IOException ignored) { }
    }
    public Map<String,Object> bootstrap() {
        Map<String,Object> result = new LinkedHashMap<String,Object>();
        result.put("token", token); result.put("version", "2.0.0"); result.put("storageRoot", paths.root().toString());
        return result;
    }
    @EventListener public void started(ServletWebServerInitializedEvent event) {
        System.out.println("数据库工作台已启动: http://127.0.0.1:" + event.getWebServer().getPort());
    }
    @Override protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        res.setHeader("X-Content-Type-Options", "nosniff");
        res.setHeader("Referrer-Policy", "no-referrer");
        res.setHeader("X-Frame-Options", "DENY");
        if (req.getRequestURI().startsWith("/api/")) {
            res.setHeader("Cache-Control", "no-store");
            boolean valid = !"cross-site".equals(req.getHeader("Sec-Fetch-Site"));
            String origin = req.getHeader("Origin");
            if (origin != null) {
                try {
                    URI uri = URI.create(origin);
                    int port = uri.getPort() < 0 ? ("https".equals(uri.getScheme()) ? 443 : 80) : uri.getPort();
                    valid &= req.getScheme().equals(uri.getScheme()) && req.getServerName().equalsIgnoreCase(uri.getHost()) && req.getServerPort() == port;
                } catch (Exception ex) { valid = false; }
            }
            String host = req.getServerName();
            valid &= "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "[::1]".equals(host) || "::1".equals(host);
            boolean read = "GET".equals(req.getMethod()) || "HEAD".equals(req.getMethod());
            if (!read) valid &= token.equals(req.getHeader("X-Toolbox-Token"));
            if (!valid) {
                res.setStatus(403); res.setContentType("application/json;charset=UTF-8");
                mapper.writeValue(res.getOutputStream(), ApiResponse.fail("本机访问校验失败，请刷新工作台后重试")); return;
            }
        }
        chain.doFilter(req, res);
    }
    @RestController public static class BootstrapController {
        private final LocalRuntime runtime;
        public BootstrapController(LocalRuntime runtime) { this.runtime = runtime; }
        @GetMapping("/api/bootstrap") public ApiResponse<Map<String,Object>> bootstrap() { return ApiResponse.ok(runtime.bootstrap()); }
    }
}
