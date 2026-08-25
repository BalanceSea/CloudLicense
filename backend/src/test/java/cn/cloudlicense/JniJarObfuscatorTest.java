package cn.cloudlicense;

import cn.cloudlicense.config.CloudLicenseProperties;
import cn.cloudlicense.obfuscation.JniJarObfuscator;
import cn.cloudlicense.obfuscation.NativeClassTransformer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
class JniJarObfuscatorTest {
    @TempDir
    Path tempDirectory;

    @Test
    void preservesResourcesAndManifestWhileTransformingClassesAndRemovingSignatures() throws IOException {
        Path input = tempDirectory.resolve("input.jar");
        Path output = tempDirectory.resolve("output.jar");
        byte[] originalClass = {1, 2, 3};
        byte[] transformedClass = {9, 8, 7};
        createInputJar(input, originalClass);

        StubClassTransformer transformer = new StubClassTransformer(transformedClass);

        new JniJarObfuscator(transformer).obfuscate(input, output);

        try (JarFile jar = new JarFile(output.toFile())) {
            assertEquals("CloudLicense-Test",
                    jar.getManifest().getMainAttributes().getValue("Implementation-Title"));
            assertNotNull(jar.getEntry("plugin.yml"));
            assertArrayEquals("name: Test\n".getBytes(StandardCharsets.UTF_8),
                    jar.getInputStream(jar.getEntry("plugin.yml")).readAllBytes());
            assertArrayEquals(transformedClass,
                    jar.getInputStream(jar.getEntry("com/example/Test.class")).readAllBytes());
            assertNull(jar.getEntry("META-INF/TEST.SF"));
        }
        assertArrayEquals(originalClass, transformer.lastInput());
    }

    private void createInputJar(Path path, byte[] classBytes) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Implementation-Title", "CloudLicense-Test");
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(path), manifest)) {
            writeEntry(jar, "plugin.yml", "name: Test\n".getBytes(StandardCharsets.UTF_8));
            writeEntry(jar, "com/example/Test.class", classBytes);
            writeEntry(jar, "META-INF/TEST.SF", "invalid signature".getBytes(StandardCharsets.UTF_8));
        }
    }

    private void writeEntry(JarOutputStream jar, String name, byte[] content) throws IOException {
        jar.putNextEntry(new JarEntry(name));
        jar.write(content);
        jar.closeEntry();
    }

    private static final class StubClassTransformer extends NativeClassTransformer {
        private final byte[] transformedClass;
        private byte[] lastInput;

        private StubClassTransformer(byte[] transformedClass) {
            super(new CloudLicenseProperties("", "", Path.of("."), "", false, 1, List.of()));
            this.transformedClass = transformedClass;
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public byte[] transform(byte[] classBytes) {
            lastInput = classBytes.clone();
            return transformedClass;
        }

        private byte[] lastInput() {
            return lastInput;
        }
    }
}
