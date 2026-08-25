package cn.cloudlicense.obfuscation;

import org.springframework.stereotype.Component;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

@Component
public class JniJarObfuscator implements JarObfuscator {
    private final NativeClassTransformer transformer;

    public JniJarObfuscator(NativeClassTransformer transformer) {
        this.transformer = transformer;
    }

    @Override
    public void obfuscate(Path input, Path output) throws IOException {
        if (!isAvailable()) {
            throw new IllegalStateException("JNI native library is not loaded");
        }
        try (JarInputStream source = new JarInputStream(new BufferedInputStream(Files.newInputStream(input)))) {
            Manifest manifest = source.getManifest();
            try (JarOutputStream target = manifest == null
                    ? new JarOutputStream(new BufferedOutputStream(Files.newOutputStream(output)))
                    : new JarOutputStream(new BufferedOutputStream(Files.newOutputStream(output)), manifest)) {
                JarEntry entry;
                while ((entry = source.getNextJarEntry()) != null) {
                    if (isInvalidatedSignature(entry.getName())) {
                        continue;
                    }
                    JarEntry copied = new JarEntry(entry.getName());
                    copied.setTime(0L);
                    target.putNextEntry(copied);
                    byte[] content = source.readAllBytes();
                    target.write(entry.getName().endsWith(".class") ? transformer.transform(content) : content);
                    target.closeEntry();
                }
            }
        }
    }

    @Override
    public boolean isAvailable() {
        return transformer.isAvailable();
    }

    private boolean isInvalidatedSignature(String entryName) {
        String normalized = entryName.toUpperCase(Locale.ROOT);
        return normalized.startsWith("META-INF/") &&
                (normalized.endsWith(".SF") || normalized.endsWith(".RSA") || normalized.endsWith(".DSA"));
    }
}
