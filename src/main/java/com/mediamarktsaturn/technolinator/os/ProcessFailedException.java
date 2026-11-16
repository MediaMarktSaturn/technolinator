package com.mediamarktsaturn.technolinator.os;

import java.util.List;

/**
 * A process exited with a non-zero status. Carries the combined stdout/stderr output, so that
 * the reason of the failure can be reported later on.
 * <p>
 * The output is deliberately not part of the exception message, as the message ends up in logs.
 */
public class ProcessFailedException extends Exception {

    private final String command;
    private final Integer exitCode;
    private final List<String> outputLines;
    private final String projectName;

    public ProcessFailedException(String command, Integer exitCode, List<String> outputLines) {
        this(command, exitCode, outputLines, null);
    }

    public ProcessFailedException(String command, Integer exitCode, List<String> outputLines, String projectName) {
        super(command + " exited with " + exitCode);
        this.command = command;
        this.exitCode = exitCode;
        this.outputLines = List.copyOf(outputLines);
        this.projectName = projectName;
    }

    public String command() {
        return command;
    }

    public Integer exitCode() {
        return exitCode;
    }

    /**
     * @return stdout and stderr in the order they were written
     */
    public List<String> outputLines() {
        return outputLines;
    }

    /**
     * @return the name of the project the failed command was analysing, if known
     */
    public String projectName() {
        return projectName;
    }

    public ProcessFailedException withProjectName(String projectName) {
        return new ProcessFailedException(command, exitCode, outputLines, projectName);
    }
}
