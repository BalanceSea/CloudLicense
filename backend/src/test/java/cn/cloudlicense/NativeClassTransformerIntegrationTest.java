package cn.cloudlicense;

import cn.cloudlicense.config.CloudLicenseProperties;
import cn.cloudlicense.obfuscation.NativeClassTransformer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class NativeClassTransformerIntegrationTest {
    @Test
    void nativeTransformationRenamesPrivateMembersAndKeepsPublicBehavior() throws Exception {
        String library = System.getenv("CLOUDLICENSE_NATIVE_LIBRARY");
        assumeTrue(library != null && !library.isBlank(),
                "CLOUDLICENSE_NATIVE_LIBRARY is required for the native integration test");

        NativeClassTransformer transformer = new NativeClassTransformer(new CloudLicenseProperties(
                "", "", Path.of("."), library, false, 1, List.of()));
        assertTrue(transformer.isAvailable());

        byte[] original = fixtureBytes();
        byte[] transformed = transformer.transform(original);
        assertFalse(Arrays.equals(original, transformed), "JNI must change the class bytes");

        Class<?> fixture = new DefiningLoader(getClass().getClassLoader()).define(transformed);
        Object instance = fixture.getConstructor().newInstance();
        assertEquals(42, fixture.getMethod("call").invoke(instance));
        assertNotNull(Arrays.stream(fixture.getDeclaredMethods())
                .filter(method -> method.getName().startsWith("cl$"))
                .findFirst()
                .orElse(null));
    }

    private byte[] fixtureBytes() throws IOException {
        String resource = "/" + NativeClassTransformerIntegrationTest.class.getName().replace('.', '/') + "$Fixture.class";
        try (var input = getClass().getResourceAsStream(resource)) {
            if (input == null) {
                throw new IOException("test fixture class was not compiled");
            }
            return input.readAllBytes();
        }
    }

    public static class Fixture {
        private int secret = 7;

        public int call() {
            return hidden(secret);
        }

        private int hidden(int value) {
            return value + 35;
        }
    }

    private static final class DefiningLoader extends ClassLoader {
        private DefiningLoader(ClassLoader parent) {
            super(parent);
        }

        private Class<?> define(byte[] bytes) {
            return defineClass(null, bytes, 0, bytes.length);
        }
    }
}
