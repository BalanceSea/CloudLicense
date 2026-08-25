package cn.cloudlicense.service;

import cn.cloudlicense.domain.Plugin;
import cn.cloudlicense.domain.PluginVersion;
import cn.cloudlicense.exception.ApiException;
import cn.cloudlicense.repository.CloudLicenseRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class PluginVersionService {
    private final CloudLicenseRepository repository;
    private final StorageService storage;

    public PluginVersionService(CloudLicenseRepository repository, StorageService storage) {
        this.repository = repository;
        this.storage = storage;
    }

    public PluginVersion upload(UUID pluginId, String version, String changelog, boolean isPublic,
                                MultipartFile file) {
        Plugin plugin = repository.findPlugin(pluginId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PLUGIN_NOT_FOUND", "插件不存在"));
        StorageService.StoredArtifact artifact = storage.obfuscateAndStore(file, plugin.slug(), version);
        PluginVersion pluginVersion = new PluginVersion(UUID.randomUUID(), plugin.id(), version.trim(),
                artifact.fileName(), artifact.path().toString(), artifact.sha256(), artifact.sizeBytes(),
                changelog == null ? "" : changelog.trim(), isPublic, OffsetDateTime.now());
        try {
            return repository.insertVersion(pluginVersion);
        } catch (DuplicateKeyException exception) {
            try {
                java.nio.file.Files.deleteIfExists(artifact.path());
            } catch (java.io.IOException ignored) {
                // The orphan is harmless and can be removed by storage maintenance.
            }
            throw new ApiException(HttpStatus.CONFLICT, "VERSION_EXISTS", "该插件版本已存在");
        }
    }

    public PluginVersion latest(String pluginSlug) {
        Plugin plugin = repository.findPlugin(pluginSlug.toLowerCase())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PLUGIN_NOT_FOUND", "插件不存在"));
        return latest(repository.findVersions(plugin.id(), true));
    }

    public PluginVersion latest(List<PluginVersion> versions) {
        return versions.stream().max((left, right) ->
                        VersionComparator.INSTANCE.compare(left.version(), right.version()))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "VERSION_NOT_FOUND", "暂无可下载版本"));
    }
}
