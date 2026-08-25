package cn.cloudlicense.obfuscation;

import java.io.IOException;
import java.nio.file.Path;

public interface JarObfuscator {
    void obfuscate(Path input, Path output) throws IOException;

    boolean isAvailable();
}

