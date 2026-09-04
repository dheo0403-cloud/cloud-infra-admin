package com.example.infra.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String resourcePath, Resource location) throws IOException {
                        Resource requestedResource = location.createRelative(resourcePath);
                        // API 경로가 아니고, 실제 리소스가 존재하지 않으면 index.html 반환
                        if (requestedResource.exists() && requestedResource.isReadable()) {
                            return requestedResource;
                        } else if (resourcePath.startsWith("api/")) {
                            return null;
                        } else {
                            return location.createRelative("index.html");
                        }
                    }
                });
    }
}
