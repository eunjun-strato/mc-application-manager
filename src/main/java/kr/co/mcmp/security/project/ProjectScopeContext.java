package kr.co.mcmp.security.project;

public record ProjectScopeContext(
        String authorization,
        String workspaceId,
        String projectId,
        String namespace) {
}
