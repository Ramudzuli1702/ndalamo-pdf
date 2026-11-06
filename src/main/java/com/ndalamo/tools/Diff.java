package com.ndalamo.tools;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Finds the shortest set of removals and additions that turns one list into another
 * (Myers' diff, the method behind most "compare" tools). Used by Compare PDF, first on
 * lines and then on the words of lines that changed.
 */
final class Diff {

    /** One step of the answer: a run of items kept, removed (from a) or added (from b). */
    record Step(Kind kind, int aStart, int aEnd, int bStart, int bEnd) {}

    enum Kind { SAME, REMOVED, ADDED }

    /**
     * Beyond this many changes the lists are treated as simply different from the first
     * difference on: the search's memory grows with the number of changes squared.
     */
    static final int MOST_CHANGES = 4000;

    private Diff() {}

    static <T> List<Step> of(List<T> a, List<T> b) {
        int n = a.size(), m = b.size();
        // Shared start and end are cut off first: usually most of the document.
        int head = 0;
        while (head < n && head < m && a.get(head).equals(b.get(head))) head++;
        int tail = 0;
        while (tail < n - head && tail < m - head && a.get(n - 1 - tail).equals(b.get(m - 1 - tail))) tail++;

        List<Step> steps = new ArrayList<>();
        if (head > 0) steps.add(new Step(Kind.SAME, 0, head, 0, head));
        middle(a, b, head, n - tail, head, m - tail, steps);
        if (tail > 0) steps.add(new Step(Kind.SAME, n - tail, n, m - tail, m));
        return merge(steps);
    }

    /** Myers' greedy search over the part between the shared start and end, then walks back the path. */
    private static <T> void middle(List<T> a, List<T> b, int aFrom, int aTo, int bFrom, int bTo, List<Step> steps) {
        int n = aTo - aFrom, m = bTo - bFrom;
        if (n == 0 && m == 0) return;
        if (n == 0 || m == 0) {
            steps.add(n == 0 ? new Step(Kind.ADDED, aFrom, aFrom, bFrom, bTo) : new Step(Kind.REMOVED, aFrom, aTo, bFrom, bFrom));
            return;
        }
        int max = Math.min(n + m, MOST_CHANGES);
        int offset = max + 1;
        int[] v = new int[2 * max + 3];
        Arrays.fill(v, -1);
        v[offset + 1] = 0;
        List<int[]> trace = new ArrayList<>();
        int found = -1;
        for (int d = 0; d <= max && found < 0; d++) {
            trace.add(v.clone());
            for (int k = -d; k <= d; k += 2) {
                int x = (k == -d || (k != d && v[offset + k - 1] < v[offset + k + 1])) ? v[offset + k + 1] : v[offset + k - 1] + 1;
                int y = x - k;
                while (x < n && y < m && a.get(aFrom + x).equals(b.get(bFrom + y))) {
                    x++;
                    y++;
                }
                v[offset + k] = x;
                if (x >= n && y >= m) {
                    found = d;
                    break;
                }
            }
        }
        if (found < 0) {
            // Too different to work out cheaply: all of one replaced by all of the other.
            steps.add(new Step(Kind.REMOVED, aFrom, aTo, bFrom, bFrom));
            steps.add(new Step(Kind.ADDED, aTo, aTo, bFrom, bTo));
            return;
        }
        // Walk back from the end, collecting single steps in reverse.
        List<Step> reversed = new ArrayList<>();
        int x = n, y = m;
        for (int d = found; d > 0; d--) {
            int[] pv = trace.get(d);
            int k = x - y;
            boolean down = k == -d || (k != d && pv[offset + k - 1] < pv[offset + k + 1]);
            int prevK = down ? k + 1 : k - 1;
            int prevX = pv[offset + prevK];
            int prevY = prevX - prevK;
            int startX = down ? prevX : prevX + 1;
            int startY = startX - k;
            if (x > startX) reversed.add(new Step(Kind.SAME, aFrom + startX, aFrom + x, bFrom + startY, bFrom + y));
            if (down) reversed.add(new Step(Kind.ADDED, aFrom + prevX, aFrom + prevX, bFrom + prevY, bFrom + prevY + 1));
            else reversed.add(new Step(Kind.REMOVED, aFrom + prevX, aFrom + prevX + 1, bFrom + prevY, bFrom + prevY));
            x = prevX;
            y = prevY;
        }
        if (x > 0) reversed.add(new Step(Kind.SAME, aFrom, aFrom + x, bFrom, bFrom + y));
        for (int i = reversed.size() - 1; i >= 0; i--) steps.add(reversed.get(i));
    }

    /** Joins neighbouring steps of the same kind, so each run is one step. */
    private static List<Step> merge(List<Step> steps) {
        List<Step> merged = new ArrayList<>();
        for (Step s : steps) {
            if (s.aStart() == s.aEnd() && s.bStart() == s.bEnd()) continue;
            Step last = merged.isEmpty() ? null : merged.get(merged.size() - 1);
            if (last != null && last.kind() == s.kind() && last.aEnd() == s.aStart() && last.bEnd() == s.bStart()) {
                merged.set(merged.size() - 1, new Step(s.kind(), last.aStart(), s.aEnd(), last.bStart(), s.bEnd()));
            } else {
                merged.add(s);
            }
        }
        return merged;
    }
}
