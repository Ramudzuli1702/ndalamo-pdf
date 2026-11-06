package com.ndalamo.tools;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The diff must describe both lists exactly, and change as little as possible. */
class DiffTest {

    @Test
    void simpleEdits() {
        List<Diff.Step> steps = Diff.of(List.of("a", "b", "c", "d"), List.of("a", "x", "c", "d", "e"));
        assertEquals(List.of(
                new Diff.Step(Diff.Kind.SAME, 0, 1, 0, 1),
                new Diff.Step(Diff.Kind.REMOVED, 1, 2, 1, 1),
                new Diff.Step(Diff.Kind.ADDED, 2, 2, 1, 2),
                new Diff.Step(Diff.Kind.SAME, 2, 4, 2, 4),
                new Diff.Step(Diff.Kind.ADDED, 4, 4, 4, 5)), steps);
        assertEquals(List.of(new Diff.Step(Diff.Kind.SAME, 0, 2, 0, 2)), Diff.of(List.of("a", "b"), List.of("a", "b")));
        assertEquals(List.of(), Diff.of(List.of(), List.of()));
        assertEquals(List.of(new Diff.Step(Diff.Kind.ADDED, 0, 0, 0, 2)), Diff.of(List.of(), List.of("a", "b")));
    }

    @Test
    void randomListsAreDescribedExactlyAndMinimally() {
        Random random = new Random(7);
        for (int round = 0; round < 600; round++) {
            List<Integer> a = randomList(random), b = randomList(random);
            List<Diff.Step> steps = Diff.of(a, b);
            List<Integer> rebuiltA = new ArrayList<>(), rebuiltB = new ArrayList<>();
            int kept = 0, ai = 0, bi = 0;
            for (Diff.Step s : steps) {
                assertEquals(ai, s.aStart(), "steps follow on in a");
                assertEquals(bi, s.bStart(), "steps follow on in b");
                if (s.kind() == Diff.Kind.SAME) {
                    assertEquals(a.subList(s.aStart(), s.aEnd()), b.subList(s.bStart(), s.bEnd()));
                    kept += s.aEnd() - s.aStart();
                }
                if (s.kind() != Diff.Kind.ADDED) rebuiltA.addAll(a.subList(s.aStart(), s.aEnd()));
                if (s.kind() != Diff.Kind.REMOVED) rebuiltB.addAll(b.subList(s.bStart(), s.bEnd()));
                ai = s.aEnd();
                bi = s.bEnd();
            }
            assertEquals(a, rebuiltA);
            assertEquals(b, rebuiltB);
            assertEquals(longestCommon(a, b), kept, "keeps as much as possible: " + a + " vs " + b);
        }
    }

    @Test
    void veryDifferentListsStillGetAnAnswer() {
        List<Integer> a = new ArrayList<>(), b = new ArrayList<>();
        for (int i = 0; i < 9000; i++) {
            a.add(i);
            b.add(-i - 1);
        }
        List<Diff.Step> steps = Diff.of(a, b);
        assertTrue(steps.stream().noneMatch(s -> s.kind() == Diff.Kind.SAME));
        assertEquals(9000, steps.stream().filter(s -> s.kind() == Diff.Kind.ADDED).mapToInt(s -> s.bEnd() - s.bStart()).sum());
    }

    private static List<Integer> randomList(Random random) {
        List<Integer> list = new ArrayList<>();
        int size = random.nextInt(14);
        for (int i = 0; i < size; i++) list.add(random.nextInt(4));
        return list;
    }

    private static int longestCommon(List<Integer> a, List<Integer> b) {
        int[][] best = new int[a.size() + 1][b.size() + 1];
        for (int i = 1; i <= a.size(); i++) {
            for (int j = 1; j <= b.size(); j++) {
                best[i][j] = a.get(i - 1).equals(b.get(j - 1)) ? best[i - 1][j - 1] + 1 : Math.max(best[i - 1][j], best[i][j - 1]);
            }
        }
        return best[a.size()][b.size()];
    }
}
