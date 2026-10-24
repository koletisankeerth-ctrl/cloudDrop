package com.clouddrop;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

// @SpringBootApplication = @Configuration + @EnableAutoConfiguration + @ComponentScan (this package and below).
// @ConfigurationPropertiesScan registers our @ConfigurationProperties classes (AppProperties).
@SpringBootApplication
@ConfigurationPropertiesScan
public class CloudDropApplication {

    public static void main(String[] args) {
        SpringApplication.run(CloudDropApplication.class, args);
    }
}
