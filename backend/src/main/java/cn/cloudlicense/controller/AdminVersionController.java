package cn.cloudlicense.controller;

import cn.cloudlicense.domain.PluginVersion;
import cn.cloudlicense.exception.ApiException;
import cn.cloudlicense.repository.CloudLicenseRepository;
import cn.cloudlicense.service.PluginVersionService;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@Validated
@RestController
@RequestMapping("/api/v1/admin/plugins/{pluginId}/versions")
public class AdminVersionController {
    private final CloudLicenseRepository repository;
    private final PluginVersionService versionService;

    public AdminVersionController(CloudLicenseRepository repository, PluginVersionService versionService) {
        this.repository = repository;
        this.versionService = versionService;
    }

    @GetMapping
    public List<PluginVersionView> versions(@PathVariable UUID pluginId) {
        if (repository.findPlugin(pluginId).isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "PLUGIN_NOT_FOUND", "插件不存在");
        }
        return repository.findVersions(pluginId, false).stream().map(PluginVersionView::from).toList();
    }

    @PostMapping(consumes = "multipart/form-data")
    public PluginVersionView upload(
            @PathVariable UUID pluginId,
            @RequestParam @Pattern(regexp = "[0-9A-Za-z][0-9A-Za-z._-]{0,63}") String version,
            @RequestParam(defaultValue = "") @Size(max = 2000) String changelog,
            @RequestParam(defaultValue = "true") boolean isPublic,
            @RequestPart("file") MultipartFile file) {
        return PluginVersionView.from(versionService.upload(pluginId, version, changelog, isPublic, file));
    }

    public record PluginVersionView(UUID id, String version, String fileName, String sha256, long sizeBytes,
                                    String changelog, boolean isPublic, Object createdAt) {
        static PluginVersionView from(PluginVersion version) {
            return new PluginVersionView(version.id(), version.version(), version.fileName(), version.sha256(),
                    version.sizeBytes(), version.changelog(), version.isPublic(), version.createdAt());
        }
    }
}

