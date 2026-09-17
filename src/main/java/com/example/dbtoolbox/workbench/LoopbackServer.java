package com.example.dbtoolbox.workbench;

import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.servlet.server.AbstractServletWebServerFactory;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

/** Run after Boot applies server.address, before any listening socket is opened. */
@Component
public class LoopbackServer implements WebServerFactoryCustomizer<AbstractServletWebServerFactory>, Ordered {
    @Override public int getOrder() { return Ordered.LOWEST_PRECEDENCE; }
    @Override public void customize(AbstractServletWebServerFactory factory) {
        if(factory.getAddress()==null || !factory.getAddress().isLoopbackAddress())
            throw new IllegalStateException("数据库工作台仅允许本机监听；server.address 必须为 loopback 地址");
    }
}
