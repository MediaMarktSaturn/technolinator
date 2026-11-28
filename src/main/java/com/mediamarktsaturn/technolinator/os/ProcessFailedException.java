package com.mediamarktsaturn.technolinator.os;

import java.util.ArrayList;
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
    private final String location;
    private final List<String> notes;

    public ProcessFailedException(String command, Integer exitCode, List<String> outputLines) {
        this(command, exitCode, outputLines, null);
    }

    public ProcessFailedException(String command, Integer exitCode, List<String> outputLines, String projectName) {
        this(command, exitCode, outputLines, projectName, null);
    }

    public ProcessFailedException(String command, Integer exitCode, List<String> outputLines, String projectName, String location) {
        this(command, exitCode, outputLines, projectName, location, List.of());
    }

    private ProcessFailedException(String command, Integer exitCode, List<String> outputLines, String projectName, String location, List<String> notes) {
        super(command + " exited with " + exitCode);
        this.command = command;
        this.exitCode = exitCode;
        this.outputLines = List.copyOf(outputLines);
        this.projectName = projectName;
        this.location = location;
        this.notes = List.copyOf(notes);
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

    /**
     * @return the folder the failed command was analysing, relative to the repository root, empty for the root itself; null if unknown
     */
    public String location() {
        return location;
    }

    /**
     * @return remarks of Technolinator that help to understand the failure, e.g. that a preparation of the analysis was skipped
     */
    public List<String> notes() {
        return notes;
    }

    public ProcessFailedException withProjectName(String projectName) {
        return new ProcessFailedException(command, exitCode, outputLines, projectName, location, notes);
    }

    public ProcessFailedException withProject(String projectName, String location) {
        return new ProcessFailedException(command, exitCode, outputLines, projectName, location, notes);
    }

    public ProcessFailedException withNote(String note) {
        var all = new ArrayList<>(notes);
        all.add(note);
        return new ProcessFailedException(command, exitCode, outputLines, projectName, location, all);
    }

    /**
     * @return the same failure, but attributed to another command
     */
    public ProcessFailedException withCommand(String command) {
        return new ProcessFailedException(command, exitCode, outputLines, projectName, location, notes);
    }
}
