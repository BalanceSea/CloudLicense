package cn.cloudlicense.controller;

import cn.cloudlicense.domain.Plugin;
import cn.cloudlicense.domain.PluginVersion;
import cn.cloudlicense.repository.CloudLicenseRepository;
import cn.cloudlicense.service.LicenseService;
import cn.cloudlicense.service.PluginVersionService;
import cn.cloudlicense.service.RequestIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class PublicController {
    private final CloudLicenseRepository repository;
    private final LicenseService licenses;
    private final PluginVersionService versions;
    private final RequestIpResolver ipResolver;

    public PublicController(CloudLicenseRepository repository, LicenseService licenses,
                            PluginVersionService versions, RequestIpResolver ipResolver) {
        this.repository = repository;
        this.licenses = licenses;
        this.versions = versions;
        this.ipResolver = ipResolver;
    }

    @PostMapping("/licenses/verify")
    public LicenseService.VerificationResult verify(@Valid @RequestBody VerifyRequest request,
                                                     HttpServletRequest httpRequest) {
        return licenses.verify(request.plugin(), request.licenseKey(), ipResolver.resolve(httpRequest));
    }

    @GetMapping("/public/plugins")
    public List<PublicPlugin> plugins() {
        return repository.findPlugins().stream().map(this::toPublicPlugin).toList();
    }

    @GetMapping("/public/plugins/{slug}/versions")
    public List<AdminVersionController.PluginVersionView> pluginVersions(@PathVariable String slug) {
        Plugin plugin = requirePlugin(slug);
        return repository.findVersions(plugin.id(), true).stream()
                .map(AdminVersionController.PluginVersionView::from).toList();
    }

    @GetMapping("/public/plugins/{slug}/latest")
    public LatestVersion latest(@PathVariable String slug) {
        PluginVersion version = versions.latest(slug);
        return LatestVersion.from(slug, version);
    }

    @GetMapping("/public/plugins/{slug}/latest/download")
    public void disabledAnonymousDownload(@PathVariable String slug) {
        throw new cn.cloudlicense.exception.ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED,
                "LOGIN_REQUIRED", "请登录用户中心下载已拥有的插件");
    }

    private PublicPlugin toPublicPlugin(Plugin plugin) {
        List<PluginVersion> available = repository.findVersions(plugin.id(), true);
        if (available.isEmpty()) {
            return new PublicPlugin(plugin.slug(), plugin.name(), plugin.description(), null, null);
        }
        PluginVersion latest = versions.latest(available);
        return new PublicPlugin(plugin.slug(), plugin.name(), plugin.description(), latest.version(),
                "/download.html");
    }

    private Plugin requirePlugin(String slug) {
        return repository.findPlugin(slug.toLowerCase()).orElseThrow(() ->
                new cn.cloudlicense.exception.ApiException(org.springframework.http.HttpStatus.NOT_FOUND,
                        "PLUGIN_NOT_FOUND", "插件不存在"));
    }

    public record VerifyRequest(
            @NotBlank @Pattern(regexp = "[a-zA-Z0-9-]{3,64}") String plugin,
            @NotBlank @Size(max = 64) String licenseKey
    ) {
    }

    public record PublicPlugin(String slug, String name, String description, String latestVersion,
                               String downloadUrl) {
    }

    public record LatestVersion(String plugin, String version, String sha256, long sizeBytes,
                                String changelog, Object publishedAt, String downloadUrl) {
        static LatestVersion from(String slug, PluginVersion version) {
            return new LatestVersion(slug, version.version(), version.sha256(), version.sizeBytes(),
                    version.changelog(), version.createdAt(),
                    "/download.html");
        }
    }
}
