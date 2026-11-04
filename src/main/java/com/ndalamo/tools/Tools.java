package com.ndalamo.tools;

import java.util.List;
import java.util.Optional;

/**
 * The list of every tool in the app, in home-screen order. The home screen,
 * search, pipeline builder and drop suggestions all read from here, so a new
 * tool only has to be added in one place.
 */
public final class Tools {

    // Empty until the first tool (Merge) arrives in build chunk 3.
    private static final List<PdfTool> ALL = List.of();

    private Tools() {}

    public static List<PdfTool> all() {
        return ALL;
    }

    public static Optional<PdfTool> find(String id) {
        return ALL.stream().filter(t -> t.id().equals(id)).findFirst();
    }
}
