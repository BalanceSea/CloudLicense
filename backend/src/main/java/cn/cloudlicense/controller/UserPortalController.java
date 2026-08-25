package cn.cloudlicense.controller;

import cn.cloudlicense.config.UserSessionFilter;
import cn.cloudlicense.domain.PluginVersion;
import cn.cloudlicense.domain.UserAccount;
import cn.cloudlicense.service.StorageService;
import cn.cloudlicense.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/user")
public class UserPortalController {
    private final UserService users;
    private final StorageService storage;

    public UserPortalController(UserService users, StorageService storage) {
        this.users = users;
        this.storage = storage;
    }

    @GetMapping("/me")
    public UserService.UserView me(HttpServletRequest request) {
        return UserService.UserView.from(currentUser(request));
    }

    @PostMapping("/logout")
    public AdminPluginController.ActionResponse logout(HttpServletRequest request) {
        users.logout((String) request.getAttribute(UserSessionFilter.TOKEN_ATTRIBUTE));
        return new AdminPluginController.ActionResponse(true, "已退出登录");
    }

    @PostMapping("/licenses/claim")
    public UserService.UserLicenseView claim(HttpServletRequest httpRequest,
                                             @Valid @RequestBody ClaimRequest request) {
        return users.claim(currentUser(httpRequest).id(), request.plugin(), request.licenseKey());
    }

    @GetMapping("/plugins")
    public List<UserService.UserPluginView> plugins(HttpServletRequest request) {
        return users.plugins(currentUser(request).id());
    }

    @PostMapping("/licenses/{licenseId}/unbind")
    public AdminPluginController.ActionResponse unbind(@PathVariable UUID licenseId, HttpServletRequest request) {
        users.unbind(currentUser(request).id(), licenseId);
        return new AdminPluginController.ActionResponse(true, "IP 绑定已解除");
    }

    @GetMapping("/plugins/{slug}/download")
    public ResponseEntity<FileSystemResource> download(@PathVariable String slug, HttpServletRequest request) {
        PluginVersion version = users.requireDownloadableVersion(currentUser(request).id(), slug);
        Path path = storage.resolveStoredPath(version.storedPath());
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/java-archive"))
                .contentLength(version.sizeBytes())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(version.fileName(), StandardCharsets.UTF_8).build().toString())
                .header("X-Checksum-SHA256", version.sha256())
                .body(new FileSystemResource(path));
    }

    private UserAccount currentUser(HttpServletRequest request) {
        return (UserAccount) request.getAttribute(UserSessionFilter.USER_ATTRIBUTE);
    }

    public record ClaimRequest(
            @NotBlank @Pattern(regexp = "[a-zA-Z0-9-]{3,64}") String plugin,
            @NotBlank @Size(max = 64) String licenseKey
    ) {
    }
}
