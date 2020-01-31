package com.ithit.webdav.samples.springbootsample.configuration;

import com.ithit.webdav.samples.springbootsample.common.ResourceReader;
import com.ithit.webdav.samples.springbootsample.extendedattributes.ExtendedAttributesExtension;
import com.ithit.webdav.samples.springbootsample.impl.CustomFolderGetHandler;
import com.ithit.webdav.samples.springbootsample.impl.WebDavEngine;
import com.ithit.webdav.server.Engine;
import com.ithit.webdav.server.util.StringUtil;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.experimental.FieldDefaults;
import org.apache.commons.io.FileUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.io.Resource;
import org.springframework.util.StreamUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurerAdapter;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;

@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
@EnableConfigurationProperties(WebDavConfigurationProperties.class)
@EnableWebMvc
@Configuration
public class WebDavConfiguration extends WebMvcConfigurerAdapter {
    final WebDavConfigurationProperties properties;
    final ResourceReader resourceReader;
    private static String rootLocalPath = null;
    @Value("classpath:handler/MyCustomHandlerPage.html")
    Resource customGetHandler;
    @Value("classpath:handler/attributesErrorPage.html")
    Resource errorPage;

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(Collections.singletonList("*"));
        configuration.setAllowedMethods(Arrays.asList("PROPFIND", "PROPPATCH", "COPY", "MOVE", "DELETE", "MKCOL", "LOCK", "UNLOCK", "PUT", "GETLIB", "VERSION-CONTROL", "CHECKIN", "CHECKOUT", "UNCHECKOUT", "REPORT", "UPDATE", "CANCELUPLOAD", "HEAD", "OPTIONS", "GET", "POST"));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registry.addResourceHandler("/wwwroot/**")
                .addResourceLocations("classpath:/wwwroot/", "/wwwroot/");
    }

    @Bean
    public WebDavEngine engine() {
        rootLocalPath = properties.getRootFolder();
        checkRootPath(properties.getRootFolder());
        String license;
        try {
            license = FileUtils.readFileToString(new File(properties.getLicense()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            license = "";
        }
        final WebDavEngine webDavEngine = new WebDavEngine(license, rootLocalPath, properties.isShowExceptions());
        final boolean extendedAttributesSupported = ExtendedAttributesExtension.isExtendedAttributesSupported(rootLocalPath);
        CustomFolderGetHandler handler = new CustomFolderGetHandler(webDavEngine.getResponseCharacterEncoding(), Engine.getVersion(), extendedAttributesSupported, customGetHandler(), errorPage(), properties.getRootContext());
        CustomFolderGetHandler handlerHead = new CustomFolderGetHandler(webDavEngine.getResponseCharacterEncoding(), Engine.getVersion(), extendedAttributesSupported, customGetHandler(), errorPage(), properties.getRootContext());
        handler.setPreviousHandler(webDavEngine.registerMethodHandler("GET", handler));
        handlerHead.setPreviousHandler(webDavEngine.registerMethodHandler("HEAD", handlerHead));
        return webDavEngine;
    }

    @Bean
    public String customGetHandler() {
        return getStreamAsString(customGetHandler);
    }

    @Bean
    public String errorPage() {
        return getStreamAsString(errorPage);
    }

    @SneakyThrows
    private String getStreamAsString(Resource customGetHandler) {
        try (InputStream is = customGetHandler.getInputStream()) {
            return StreamUtils.copyToString(is, StandardCharsets.UTF_8);
        }
    }

    private void checkRootPath(String rootPath) {
        String realPath = resourceReader.getRootFolder();
        if (StringUtil.isNullOrEmpty(rootPath)) {
            createDefaultPath();
        } else {
            if (Files.exists(Paths.get(rootPath))) {
                return;
            }
            try {
                if (Files.exists(Paths.get(realPath, rootPath))) {
                    rootLocalPath = Paths.get(realPath, rootPath).toString();
                } else {
                    resourceReader.readFiles();
                    rootLocalPath = resourceReader.getDefaultPath();
                }
            } catch (Exception ignored) {
                createDefaultPath();
            }
        }
    }

    private void createDefaultPath() {
        resourceReader.readFiles();
        rootLocalPath = resourceReader.getDefaultPath();
    }
}
