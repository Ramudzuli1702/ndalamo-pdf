package com.ndalamo.models;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One tab in the Page workspace: a list of pages being edited, with undo.
 *
 * Every edit saves the page list as it was before, so Undo simply puts it back.
 * Each edit also notes what kind of change it was, for the "Save changes?" dialog
 * ("You deleted 2 pages and rotated 1."). Nothing here touches a PDF file.
 */
public class WorkspaceDocument {

    /** A kind of change, with the words used to describe it. */
    public enum Change {
        DELETED("deleted"), ROTATED("rotated"), MOVED("moved"), ADDED("added"), CROPPED("cropped"), SPLIT_OFF("split off");

        final String verb;
        Change(String verb) { this.verb = verb; }
    }

    private record Step(List<WorkspacePage> pagesBefore, Change change, int count) {}

    private final Path origin;              // the file opened, for naming the result; null for a split-off part
    private String name;
    private List<WorkspacePage> pages;
    private final Deque<Step> history = new ArrayDeque<>();

    public WorkspaceDocument(Path origin, String name, List<WorkspacePage> pages) {
        this.origin = origin;
        this.name = name;
        this.pages = new ArrayList<>(pages);
    }

    public Path getOrigin() { return origin; }
    public String getName() { return name; }
    public List<WorkspacePage> getPages() { return List.copyOf(pages); }
    public int size() { return pages.size(); }
    public boolean isChanged() { return !history.isEmpty(); }
    public boolean canUndo() { return !history.isEmpty(); }

    // ── Edits ────────────────────────────────────────────────────────────

    public void delete(Set<Long> ids) {
        int count = (int) pages.stream().filter(p -> ids.contains(p.id())).count();
        if (count == 0) return;
        record(Change.DELETED, count);
        pages.removeIf(p -> ids.contains(p.id()));
    }

    /** Turns the chosen pages by {@code degrees} clockwise (90 or -90). */
    public void rotate(Set<Long> ids, int degrees) {
        int count = (int) pages.stream().filter(p -> ids.contains(p.id())).count();
        if (count == 0) return;
        record(Change.ROTATED, count);
        pages.replaceAll(p -> ids.contains(p.id()) ? p.turned(degrees) : p);
    }

    public void crop(Set<Long> ids, WorkspacePage.Crop crop) {
        int count = (int) pages.stream().filter(p -> ids.contains(p.id()) && !p.isBlank()).count();
        if (count == 0) return;
        record(Change.CROPPED, count);
        pages.replaceAll(p -> ids.contains(p.id()) && !p.isBlank() ? p.cropped(crop) : p);
    }

    /** Puts a blank page after the given position (0 = before the first page). */
    public WorkspacePage insertBlank(int position) {
        record(Change.ADDED, 1);
        WorkspacePage blank = WorkspacePage.blank();
        pages.add(Math.max(0, Math.min(position, pages.size())), blank);
        return blank;
    }

    /** Adds pages (from another file, or moved from another tab) at a position; -1 for the end. */
    public void add(List<WorkspacePage> newPages, int position, Change change) {
        if (newPages.isEmpty()) return;
        record(change, newPages.size());
        int at = position < 0 || position > pages.size() ? pages.size() : position;
        pages.addAll(at, newPages);
    }

    /**
     * Moves the chosen pages so they sit together, in their current order, in front of
     * the page {@code beforeId} — or at the end if that's -1 or one of the moved pages.
     */
    public void move(Set<Long> ids, long beforeId) {
        List<WorkspacePage> moving = pages.stream().filter(p -> ids.contains(p.id())).toList();
        if (moving.isEmpty()) return;
        List<WorkspacePage> rest = new ArrayList<>(pages.stream().filter(p -> !ids.contains(p.id())).toList());
        int at = rest.size();
        for (int i = 0; i < rest.size(); i++) {
            if (rest.get(i).id() == beforeId) {
                at = i;
                break;
            }
        }
        rest.addAll(at, moving);
        if (rest.equals(pages)) return;             // dropped where they already were
        record(Change.MOVED, moving.size());
        pages = rest;
    }

    /** Takes out the chosen pages (for "Move to another file") and returns them. */
    public List<WorkspacePage> takeOut(Set<Long> ids, Change change) {
        List<WorkspacePage> taken = pages.stream().filter(p -> ids.contains(p.id())).toList();
        if (taken.isEmpty()) return taken;
        record(change, taken.size());
        pages.removeIf(p -> ids.contains(p.id()));
        return taken;
    }

    /** Takes out everything after the given page (for "Split after selection") and returns it. */
    public List<WorkspacePage> splitAfter(long lastKeptId) {
        int index = indexOf(lastKeptId);
        if (index < 0 || index == pages.size() - 1) return List.of();
        List<WorkspacePage> after = new ArrayList<>(pages.subList(index + 1, pages.size()));
        record(Change.SPLIT_OFF, after.size());
        pages = new ArrayList<>(pages.subList(0, index + 1));
        return after;
    }

    public boolean undo() {
        Step last = history.poll();
        if (last == null) return false;
        pages = new ArrayList<>(last.pagesBefore());
        return true;
    }

    /** Called after saving: what's on screen now matches the saved file. */
    public void markSaved() {
        history.clear();
    }

    private void record(Change change, int count) {
        history.push(new Step(List.copyOf(pages), change, count));
    }

    // ── Questions the screen asks ────────────────────────────────────────

    public int indexOf(long id) {
        for (int i = 0; i < pages.size(); i++) if (pages.get(i).id() == id) return i;
        return -1;
    }

    /** Page positions (from 1) of the chosen pages, in order. */
    public List<Integer> positionsOf(Collection<Long> ids) {
        List<Integer> positions = new ArrayList<>();
        for (int i = 0; i < pages.size(); i++) if (ids.contains(pages.get(i).id())) positions.add(i + 1);
        return positions;
    }

    /** "You deleted 2 pages and rotated 1." — for the "Save changes?" dialog. Empty if nothing changed. */
    public String changeSummary() {
        Map<Change, Integer> totals = new LinkedHashMap<>();
        // Oldest first, so the sentence follows the order things were done.
        history.descendingIterator().forEachRemaining(s -> totals.merge(s.change(), s.count(), Integer::sum));
        if (totals.isEmpty()) return "";
        List<String> parts = new ArrayList<>();
        boolean first = true;
        for (Map.Entry<Change, Integer> e : totals.entrySet()) {
            int n = e.getValue();
            parts.add(e.getKey().verb + " " + (first ? n + (n == 1 ? " page" : " pages") : String.valueOf(n)));
            first = false;
        }
        String joined = parts.size() == 1 ? parts.get(0)
                : String.join(", ", parts.subList(0, parts.size() - 1)) + " and " + parts.get(parts.size() - 1);
        return "You " + joined + ".";
    }
}
