package com.example.dbtoolbox.workbench;

import com.example.dbtoolbox.common.StoragePaths;
import com.example.dbtoolbox.config.ToolboxProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.mock.web.*;
import java.nio.file.Path;
import java.net.InetAddress;
import static org.junit.jupiter.api.Assertions.*;

class LocalRuntimeTest {
    @TempDir Path root;
    LocalRuntime runtime(){ToolboxProperties p=new ToolboxProperties();p.setStorageRoot(root.toString());return new LocalRuntime(new StoragePaths(p),new ObjectMapper());}
    int request(LocalRuntime runtime,String method,String remote,String host,String origin,String fetch,String token)throws Exception{
        MockHttpServletRequest req=new MockHttpServletRequest(method,"/custom/api/bootstrap");req.setContextPath("/custom");req.setRemoteAddr(remote);req.setServerName(host);req.setServerPort(8080);
        req.addHeader("X-Forwarded-For","127.0.0.1");if(origin!=null)req.addHeader("Origin",origin);if(fetch!=null)req.addHeader("Sec-Fetch-Site",fetch);if(token!=null)req.addHeader("X-Toolbox-Token",token);
        MockHttpServletResponse res=new MockHttpServletResponse();runtime.doFilter(req,res,(a,b)->{});return res.getStatus();
    }
    @Test void networkAndBrowserGuardMatrixIncludesContextPath()throws Exception{
        LocalRuntime r=runtime();String token=(String)r.bootstrap().get("token");
        assertEquals(200,request(r,"GET","127.0.0.1","127.0.0.1",null,null,null));
        assertEquals(200,request(r,"POST","::1","localhost","http://localhost:8080","same-origin",token));
        assertEquals(403,request(r,"GET","192.0.2.1","127.0.0.1",null,null,null));
        assertEquals(403,request(r,"POST","192.0.2.1","127.0.0.1",null,null,token));
        assertEquals(403,request(r,"POST","127.0.0.1","127.0.0.1",null,null,null));
        assertEquals(403,request(r,"GET","127.0.0.1","evil.invalid",null,null,null));
        assertEquals(403,request(r,"GET","127.0.0.1","127.0.0.1","http://evil.invalid",null,null));
        assertEquals(403,request(r,"GET","127.0.0.1","127.0.0.1",null,"cross-site",null));
        assertEquals(403,request(r,"GET","127.0.0.1","localhost","http://localhost:9090",null,null));
        assertFalse(LocalRuntime.isLoopback("localhost"));assertFalse(LocalRuntime.isLoopback(null));
    }
    @Test void nonLoopbackBindingRejectedBeforeServerCreation()throws Exception{
        LoopbackServer guard=new LoopbackServer();TomcatServletWebServerFactory f=new TomcatServletWebServerFactory();
        assertThrows(IllegalStateException.class,()->guard.customize(f));
        for(String ip:new String[]{"0.0.0.0","::","192.0.2.1"}){f.setAddress(InetAddress.getByName(ip));assertThrows(IllegalStateException.class,()->guard.customize(f));}
        for(String ip:new String[]{"127.0.0.1","::1"}){f.setAddress(InetAddress.getByName(ip));assertDoesNotThrow(()->guard.customize(f));}
    }
    @org.springframework.web.bind.annotation.RestController
    static class ProbeController {
        @org.springframework.web.bind.annotation.PostMapping("/api/probe")
        public Object fail(){throw new IllegalStateException("password=synthetic-http-secret SQL private fixture");}
    }
    @Test void httpBootstrapAndMutationErrorsUseTheRegisteredFilterAndAdvice()throws Exception{
        LocalRuntime r=runtime();
        org.springframework.test.web.servlet.MockMvc mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders
                .standaloneSetup(new LocalRuntime.BootstrapController(r),new ProbeController())
                .setControllerAdvice(new com.example.dbtoolbox.common.GlobalExceptionHandler()).addFilters(r).build();
        org.springframework.test.web.servlet.MvcResult result=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/bootstrap"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn();
        String token=new ObjectMapper().readTree(result.getResponse().getContentAsString()).path("data").path("token").asText();assertFalse(token.isEmpty());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/probe"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        String message=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/probe").header("X-Toolbox-Token",token))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isInternalServerError()).andReturn().getResponse().getContentAsString();
        assertFalse(message.contains("synthetic-http-secret"));assertFalse(message.contains("private fixture"));
    }

}
