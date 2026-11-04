package com.ndalamo.tools;

/**
 * A whole-run problem a tool can explain in plain words — e.g. "OCR couldn't start
 * on this PC because…". The error dialog shows the message as it is, instead of the
 * general "Something went wrong", so write it for the user (SPEC §6.3: what happened,
 * the original is safe, what to do next).
 */
public class ToolProblem extends RuntimeException {

    public ToolProblem(String plainMessage, Throwable cause) {
        super(plainMessage, cause);
    }
}
