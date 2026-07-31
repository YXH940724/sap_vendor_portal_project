package com.yxh.sapvendorportal;

import com.yxh.sapvendorportal.config.EnvFileLoader;
import com.yxh.sapvendorportal.config.PortalProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(PortalProperties.class)
public class SapVendorPortalApplication {
    public static void main(String[] args) {
        EnvFileLoader.load();
        SpringApplication.run(SapVendorPortalApplication.class, args);
    }
}
