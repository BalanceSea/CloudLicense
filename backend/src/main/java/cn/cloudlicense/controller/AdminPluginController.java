package cn.cloudlicense.controller;

import cn.cloudlicense.domain.License;
import cn.cloudlicense.domain.Plugin;
import cn.cloudlicense.domain.PluginVersion;
import cn.cloudlicense.exception.ApiException;
import cn.cloudlicense.repository.CloudLicenseRepository;
import cn.cloudlicense.service.LicenseService;
import cn.cloudlicense.service.PluginVersionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminPluginController {
    private final CloudLicenseRepository repository;
    private final LicenseService licenseService;
    private final PluginVersionService versionService;

    public AdminPluginController(CloudLicenseRepository repository, LicenseService licenseService,
                                 PluginVersionService versionService) {
        this.repository = repository;
        this.licenseService = licenseService;
        this.versionService = versionService;
    }

    @GetMapping("/plugins")
    public List<PluginSummary> plugins() {
        return repository.findPlugins().stream().map(plugin -> {
            List<PluginVersion> versions = repository.findVersions(plugin.id(), false);
            String latest = versions.isEmpty() ? null : versionService.latest(versions).version();
            return new PluginSummary(plugin, repository.countLicenses(plugin.id()), latest);
        }).toList();
    }

    @PostMapping("/plugins")
    public Plugin createPlugin(@Valid @RequestBody CreatePluginRequest request) {
        try {
            return repository.createPlugin(request.slug().toLowerCase(), request.name().trim(),
                    defaultString(request.description()), defaultMessage(request.verificationMessage()));
        } catch (DuplicateKeyException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "PLUGIN_EXISTS", "插件标识已存在");
        }
    }

    @GetMapping("/plugins/{pluginId}/licenses")
    public LicensePage licenses(@PathVariable UUID pluginId,
                                @RequestParam(defaultValue = "1") @Min(1) int page,
                                @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize) {
        requirePlugin(pluginId);
        List<LicenseView> items = repository.findLicenses(pluginId, pageSize, (page - 1) * pageSize)
                .stream().map(LicenseView::from).toList();
        return new LicensePage(items, page, pageSize, repository.countLicenses(pluginId));
    }

    @PostMapping("/plugins/{pluginId}/licenses")
    public LicenseService.GeneratedBatch generate(@PathVariable UUID pluginId,
                                                   @Valid @RequestBody GenerateLicenseRequest request) {
        return licenseService.generate(pluginId, request.count(), request.durationDays(), request.customMessage());
    }

    @PatchMapping("/licenses/{licenseId}/status")
    public ActionResponse updateStatus(@PathVariable UUID licenseId, @Valid @RequestBody StatusRequest request) {
        if (!repository.updateLicenseStatus(licenseId, request.status())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "LICENSE_NOT_FOUND", "授权记录不存在");
        }
        return new ActionResponse(true, "授权状态已更新");
    }

    @PostMapping("/licenses/{licenseId}/unbind")
    public ActionResponse unbind(@PathVariable UUID licenseId) {
        if (!repository.unbindLicense(licenseId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "LICENSE_NOT_FOUND", "授权记录不存在");
        }
        return new ActionResponse(true, "IP 绑定已解除");
    }

    private void requirePlugin(UUID pluginId) {
        if (repository.findPlugin(pluginId).isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "PLUGIN_NOT_FOUND", "插件不存在");
        }
    }

    private String defaultString(String value) {
        return value == null ? "" : value.trim();
    }

    private String defaultMessage(String value) {
        return value == null || value.isBlank() ? "授权验证通过" : value.trim();
    }

    public record CreatePluginRequest(
            @NotBlank @Pattern(regexp = "[a-z0-9][a-z0-9-]{1,62}[a-z0-9]") String slug,
            @NotBlank @Size(max = 100) String name,
            @Size(max = 500) String description,
            @Size(max = 300) String verificationMessage
    ) {
    }

    public record GenerateLicenseRequest(
            @Min(1) @Max(100) int count,
            @Min(1) @Max(3650) Integer durationDays,
            @Size(max = 300) String customMessage
    ) {
    }

    public record StatusRequest(@Pattern(regexp = "ACTIVE|REVOKED") String status) {
    }

    public record PluginSummary(Plugin plugin, long licenseCount, String latestVersion) {
    }

    public record LicensePage(List<LicenseView> items, int page, int pageSize, long total) {
    }

    public record LicenseView(UUID id, String key, String status, Object expiresAt, String boundIp,
                              String customMessage, Object createdAt, Object lastVerifiedAt) {
        static LicenseView from(License license) {
            return new LicenseView(license.id(), license.keyPrefix() + "••••••••••••", license.status(),
                    license.expiresAt(), license.boundIp(), license.customMessage(), license.createdAt(),
                    license.lastVerifiedAt());
        }
    }

    public record ActionResponse(boolean success, String message) {
    }
}

