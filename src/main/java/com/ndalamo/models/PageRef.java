package com.ndalamo.models;

import java.nio.file.Path;

/**
 * One page of one file, numbered from 1 — e.g. page 3 of Chapter2.pdf.
 * Used for the page order the user drags into place in Merge's preview.
 */
public record PageRef(Path file, int page) {}
