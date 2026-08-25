package cn.cloudlicense.service;

import cn.cloudlicense.config.CloudLicenseProperties;
import cn.cloudlicense.exception.ApiException;
import cn.cloudlicense.obfuscation.JarObfuscator;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import java.util.jar.JarFile;

@Service
public class StorageService {
    private final Path root;
    private final JarObfuscator obfuscator;

    public StorageService(CloudLicenseProperties properties, JarObfuscator obfuscator) {
        this.root = properties.storageRoot().toAbsolutePath().normalize();
        this.obfuscator = obfuscator;
    }

    public StoredArtifact obfuscateAndStore(MultipartFile upload, String pluginSlug, String version) {
        validateFileName(upload);
        if (!obfuscator.isAvailable()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "OBFUSCATOR_UNAVAILABLE",
                    "JNI 混淆库未加载，请配置 CLOUDLICENSE_NATIVE_LIBRARY");
        }

        try {
            Files.createDirectories(root);
            Path tempInput = Files.createTempFile(root, "upload-", ".jar");
            Path tempOutput = Files.createTempFile(root, "obfuscated-", ".jar");
            try {
                upload.transferTo(tempInput);
                validateJar(tempInput);
                obfuscator.obfuscate(tempInput, tempOutput);
                validateJar(tempOutput);

                Path destinationDirectory = root.resolve(pluginSlug).resolve(safeSegment(version)).normalize();
                ensureWithinRoot(destinationDirectory);
                Files.createDirectories(destinationDirectory);
                String fileName = pluginSlug + '-' + safeSegment(version) + '-' + UUID.randomUUID() + ".jar";
                Path destination = destinationDirectory.resolve(fileName).normalize();
                ensureWithinRoot(destination);
                Files.move(tempOutput, destination, StandardCopyOption.ATOMIC_MOVE);
                return new StoredArtifact(destination, fileName, Files.size(destination), sha256(destination));
            } finally {
                Files.deleteIfExists(tempInput);
                Files.deleteIfExists(tempOutput);
            }
        } catch (ApiException exception) {
            throw exception;
        } catch (IOException | IllegalStateException exception) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "OBFUSCATION_FAILED",
                    "JAR 混淆失败，请检查文件或 JNI 运行环境");
        }
    }

    public Path resolveStoredPath(String storedPath) {
        Path path = Path.of(storedPath).toAbsolutePath().normalize();
        ensureWithinRoot(path);
        if (!Files.isRegularFile(path)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "FILE_NOT_FOUND", "插件文件不存在");
        }
        return path;
    }

    private void validateFileName(MultipartFile upload) {
        String name = upload.getOriginalFilename();
        if (upload.isEmpty() || name == null || !name.toLowerCase().endsWith(".jar")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_JAR", "仅支持非空 JAR 文件");
        }
    }

    private void validateJar(Path path) throws IOException {
        try (JarFile jar = new JarFile(path.toFile())) {
            boolean isPlugin = jar.getEntry("plugin.yml") != null || jar.getEntry("paper-plugin.yml") != null;
            if (!isPlugin) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "NOT_A_MINECRAFT_PLUGIN",
                        "JAR 中未找到 plugin.yml 或 paper-plugin.yml");
            }
        }
    }

    private String safeSegment(String value) {
        String normalized = value == null ? "" : value.trim();
        if (!normalized.matches("[0-9A-Za-z][0-9A-Za-z._-]{0,63}")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_VERSION", "版本号格式不合法");
        }
        return normalized;
    }

    private void ensureWithinRoot(Path path) {
        if (!path.startsWith(root)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PATH", "文件路径不合法");
        }
    }

    private String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record StoredArtifact(Path path, String fileName, long sizeBytes, String sha256) {
    }
}

