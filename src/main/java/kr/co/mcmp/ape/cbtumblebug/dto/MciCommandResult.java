package kr.co.mcmp.ape.cbtumblebug.dto;

/**
 * Result of a command executed on one VM through CB-Tumblebug.
 */
public record MciCommandResult(String stdout, String stderr) {

    public MciCommandResult {
        stdout = stdout == null ? "" : stdout;
        stderr = stderr == null ? "" : stderr;
    }
}
