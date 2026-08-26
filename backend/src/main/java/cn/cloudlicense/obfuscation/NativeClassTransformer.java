package cn.cloudlicense.obfuscation;

import cn.cloudlicense.config.CloudLicenseProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class NativeClassTransformer {
    private static final Logger log = LoggerFactory.getLogger(NativeClassTransformer.class);
    private final boolean available;

    public NativeClassTransformer(CloudLicenseProperties properties) {
        String configuredPath = properties.nativeLibrary();
        if (configuredPath == null || configuredPath.isBlank()) {
            available = false;
            log.warn("JNI obfuscator is disabled: CLOUDLICENSE_NATIVE_LIBRARY is not configured");
            return;
        }
        Path library = Path.of(configuredPath).toAbsolutePath().normalize();
        if (!Files.isRegularFile(library)) {
            available = false;
            log.warn("JNI obfuscator is disabled: configured library does not exist");
            return;
        }
        System.load(library.toString());
        available = true;
        log.info("JNI obfuscator loaded: private member renaming and debug metadata stripping enabled");
    }

    public boolean isAvailable() {
        return available;
    }

    public byte[] transform(byte[] classBytes) {
        if (!available) {
            throw new IllegalStateException("JNI obfuscator is unavailable");
        }
        return transformClass(classBytes);
    }

    private native byte[] transformClass(byte[] classBytes);
}
