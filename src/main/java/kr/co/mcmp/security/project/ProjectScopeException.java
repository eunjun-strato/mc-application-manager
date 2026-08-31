package kr.co.mcmp.security.project;

import org.springframework.http.HttpStatus;

import lombok.Getter;

@Getter
public class ProjectScopeException extends RuntimeException {

    private final HttpStatus status;

    private ProjectScopeException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public static ProjectScopeException badRequest(String message) {
        return new ProjectScopeException(HttpStatus.BAD_REQUEST, message);
    }

    public static ProjectScopeException unauthorized(String message) {
        return new ProjectScopeException(HttpStatus.UNAUTHORIZED, message);
    }

    public static ProjectScopeException forbidden(String message) {
        return new ProjectScopeException(HttpStatus.FORBIDDEN, message);
    }

    public static ProjectScopeException notFound(String message) {
        return new ProjectScopeException(HttpStatus.NOT_FOUND, message);
    }

    public static ProjectScopeException serviceUnavailable(String message) {
        return new ProjectScopeException(HttpStatus.SERVICE_UNAVAILABLE, message);
    }
}
