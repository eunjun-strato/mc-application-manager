package kr.co.mcmp.softwarecatalog.docker.model;

import java.util.regex.Pattern;

/**
 * Identifies the single VM on which a Docker command is executed through
 * CB-Tumblebug's SSH command API.
 */
public record DockerTarget(String namespace, String mciId, String vmId) {

    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    public DockerTarget {
        validate("namespace", namespace);
        validate("mciId", mciId);
        validate("vmId", vmId);
    }

    private static void validate(String field, String value) {
        if (value == null || !SAFE_ID.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid Docker target " + field + ": " + value);
        }
    }
}
