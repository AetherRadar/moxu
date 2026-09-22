package com.moxu.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "Moxu")
public class MoxuConfig {
    
    /**
     * 数据目录路径
     */
    private String dataDir = "../data";
}
