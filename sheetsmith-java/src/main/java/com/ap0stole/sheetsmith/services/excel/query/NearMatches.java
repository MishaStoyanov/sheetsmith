package com.ap0stole.sheetsmith.services.excel.query;

import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFSheet;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * What the person probably meant, when an exact search found nothing.
 * <p>
 * A search that returns zero is usually not a question about an empty sheet — it is a spelling.
 * Somebody types "Betaq" for "Beta Ltd", or writes the name in the alphabet they think in while the
 * sheet holds it in another. The honest answer to that is not "no rows matched" and not a second
 * guess by the model; it is "I could not find that — did you mean this?", with the candidates found
 * here rather than by reading the sheet to the model.
 * <p>
 * That last part is the point of doing it in Java. Offering a suggestion needs the column compared
 * against, and comparing it here returns a handful of values somebody can choose between, where
 * handing the column to the model to scan would return the column.
 */
final class NearMatches {

    private NearMatches() {
    }

    /**
     * Below this the two strings are different words rather than one word typed badly.
     * <p>
     * Roughly one wrong character in four. It was set lower until "Gadget Z" was offered as a
     * suggestion for "Widget A" — three edits across eight characters, which is arithmetically
     * close and is plainly a different product. A suggestion that is wrong is worse than none: it
     * turns "there is no such row" into a question nobody asked.
     */
    private static final double THRESHOLD = 0.72;

    /** More than a person can choose between; past this the list stops being a suggestion. */
    private static final int MAX_SUGGESTIONS = 5;

    /** A search is a question, not a scan: this bounds the work on a sheet with a million rows. */
    private static final int MAX_ROWS_SCANNED = 5000;

    /**
     * Cyrillic to the Latin it is usually standing in for.
     * <p>
     * Present because the first search that failed in practice was not a typo: the question was
     * asked in Ukrainian about a company whose name the sheet spells in Latin, and no amount of
     * edit distance brings "бети" near "Beta" while they are in different alphabets.
     */
    private static final String CYRILLIC = "абвгдеёжзийклмнопрстуфхцчшщъыьэюяієїґ";
    private static final String[] LATIN = {
            "a", "b", "v", "g", "d", "e", "e", "zh", "z", "i", "y", "k", "l", "m", "n", "o", "p",
            "r", "s", "t", "u", "f", "h", "c", "ch", "sh", "sch", "", "y", "", "e", "yu", "ya",
            "i", "ie", "i", "g"};

    /**
     * Values from {@code column} that resemble {@code wanted}, best first.
     *
     * @return an empty list when nothing is close enough, which is a real answer: it means the value
     *         is absent rather than misspelt, and the caller should say so
     */
    static List<String> suggest(XSSFSheet sheet, CellRangeAddress range, int column, String wanted,
                                FormulaEvaluator evaluator) {
        if (wanted == null || wanted.isBlank()) {
            return List.of();
        }
        String needle = normalise(wanted);
        if (needle.isEmpty()) {
            return List.of();
        }

        Set<String> seen = new LinkedHashSet<>();
        List<Scored> scored = new ArrayList<>();
        int lastRow = Math.min(range.getLastRow(), range.getFirstRow() + MAX_ROWS_SCANNED - 1);
        for (int r = range.getFirstRow(); r <= lastRow; r++) {
            Object value = QuerySupport.cellValue(sheet, r, column, evaluator);
            if (value == null) {
                continue;
            }
            String text = String.valueOf(value).trim();
            if (text.isEmpty() || !seen.add(text)) {
                continue;
            }
            double score = score(needle, normalise(text));
            if (score >= THRESHOLD) {
                scored.add(new Scored(text, score));
            }
        }

        return scored.stream()
                .sorted(Comparator.comparingDouble(Scored::score).reversed())
                .limit(MAX_SUGGESTIONS)
                .map(Scored::value)
                .toList();
    }

    private record Scored(String value, double score) {
    }

    /**
     * How alike two already-normalised strings are, on a scale where 1 is the same string.
     * <p>
     * Three questions rather than one, because they fail differently. Containment catches a name
     * typed in part — "beta" inside "beta ltd" — which edit distance scores badly precisely because
     * the rest of the name is there. Whole-string distance catches a typo. Word-by-word distance
     * catches a typo in a name that has other words in it, which is the common case and the one the
     * other two both miss.
     */
    private static double score(String needle, String candidate) {
        if (candidate.isEmpty()) {
            return 0;
        }
        if (candidate.contains(needle) || needle.contains(candidate)) {
            return 0.95;
        }
        double best = ratio(needle, candidate);
        for (String word : candidate.split(" ")) {
            if (!word.isEmpty()) {
                best = Math.max(best, ratio(needle, word));
            }
        }
        return best;
    }

    private static double ratio(String a, String b) {
        int longest = Math.max(a.length(), b.length());
        return longest == 0 ? 1 : 1.0 - (double) distance(a, b) / longest;
    }

    /**
     * Damerau-Levenshtein: the fewest edits turning one string into the other, counting a swap of
     * two neighbouring characters as one.
     * <p>
     * Plain Levenshtein charges two for that swap — a delete and an insert — which is arithmetically
     * fair and wrong about people. Typing "Nroth" for "North" is one slip of the fingers, and at two
     * edits over five characters it scored below the threshold and was not offered, while "Nrth"
     * (a plain omission, one edit) was. The most ordinary typo there is was the one the suggestion
     * missed.
     * <p>
     * Three rows rather than two, because a transposition looks two rows back. Still linear in the
     * shorter string, and this runs once per distinct value in the column.
     */
    private static int distance(String a, String b) {
        int[] twoBack = new int[b.length() + 1];
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int substitute = previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
                int best = Math.min(substitute, Math.min(previous[j] + 1, current[j - 1] + 1));
                boolean swapped = i > 1 && j > 1
                        && a.charAt(i - 1) == b.charAt(j - 2)
                        && a.charAt(i - 2) == b.charAt(j - 1);
                if (swapped) {
                    best = Math.min(best, twoBack[j - 2] + 1);
                }
                current[j] = best;
            }
            int[] spare = twoBack;
            twoBack = previous;
            previous = current;
            current = spare;
        }
        return previous[b.length()];
    }

    /**
     * One spelling to compare against: lower case, no accents, one alphabet, single spaces.
     * <p>
     * Everything that is not a letter, a digit or a space goes, so "Beta Ltd." and "beta ltd" are
     * the same word to this and a full stop is not a difference worth reporting.
     */
    private static String normalise(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        StringBuilder latin = new StringBuilder(lower.length());
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            int cyrillic = CYRILLIC.indexOf(c);
            latin.append(cyrillic >= 0 ? LATIN[cyrillic] : c);
        }
        String flattened = Normalizer.normalize(latin.toString(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return flattened.replaceAll("[^a-z0-9 ]", " ").replaceAll(" +", " ").trim();
    }
}
