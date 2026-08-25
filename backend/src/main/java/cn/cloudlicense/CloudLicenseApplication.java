package cn.cloudlicense;

import cn.cloudlicense.config.CloudLicenseProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@EnableConfigurationProperties(CloudLicenseProperties.class)
public class CloudLicenseApplication {
    public static void main(String[] args) {
        SpringApplication.run(CloudLicenseApplication.class, args);
    }
}
