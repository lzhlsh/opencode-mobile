package com.example.opencodemobile;

import android.graphics.Color;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.LeadingMarginSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.style.URLSpan;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class MarkdownRenderer {
    private static final Pattern INLINE = Pattern.compile(
            "\\*\\*(.+?)\\*\\*|__(.+?)__|(?<!\\*)\\*([^*]+)\\*|(?<!_)_([^_]+)_|`([^`]+)`|\\[([^\\]]+)]\\((https?://[^\\s)]+)\\)");
    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+)$");
    private static final Pattern ORDERED_LIST = Pattern.compile("^(\\s*)\\d+[.)]\\s+(.+)$");
    private static final Pattern UNORDERED_LIST = Pattern.compile("^(\\s*)[-*+]\\s+(.+)$");
    private static final Pattern TABLE_SEPARATOR = Pattern.compile(
            "^\\s*\\|?\\s*:?-{3,}:?\\s*(\\|\\s*:?-{3,}:?\\s*)+\\|?\\s*$");
    private static final int MAX_TABLE_COLUMN_WIDTH = 18;

    private MarkdownRenderer() {}

    static SpannableStringBuilder render(String markdown) {
        SpannableStringBuilder result = new SpannableStringBuilder();
        String normalized = markdown.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalized.split("\n", -1);
        boolean inCode = false;
        boolean previousBlank = false;

        for (int lineIndex = 0; lineIndex < lines.length; lineIndex++) {
            String originalLine = lines[lineIndex];
            if (!inCode && lineIndex + 1 < lines.length && isTableHeader(originalLine, lines[lineIndex + 1])) {
                int tableEnd = lineIndex + 2;
                while (tableEnd < lines.length && lines[tableEnd].contains("|")
                        && !lines[tableEnd].trim().isEmpty()) {
                    tableEnd++;
                }
                appendTable(result, lines, lineIndex, tableEnd);
                result.append('\n');
                lineIndex = tableEnd - 1;
                previousBlank = false;
                continue;
            }
            String trimmed = originalLine.trim();
            if (trimmed.startsWith("```")) {
                inCode = !inCode;
                previousBlank = false;
                continue;
            }
            if (trimmed.isEmpty()) {
                if (result.length() > 0 && !previousBlank) result.append('\n');
                previousBlank = true;
                continue;
            }

            int start = result.length();
            if (inCode) {
                result.append(originalLine);
                result.setSpan(new TypefaceSpan("monospace"), start, result.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                result.setSpan(new BackgroundColorSpan(0xFFF0F1F3), start, result.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (trimmed.matches("([-*_]\\s*){3,}")) {
                result.append("────────────────────");
                result.setSpan(new ForegroundColorSpan(0xFF737780), start, result.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else {
                String line = originalLine;
                Matcher heading = HEADING.matcher(line);
                Matcher ordered = ORDERED_LIST.matcher(line);
                Matcher unordered = UNORDERED_LIST.matcher(line);
                boolean isHeading = heading.matches();
                boolean isList = false;
                boolean isQuote = false;
                if (isHeading) {
                    line = heading.group(2);
                } else if (ordered.matches()) {
                    line = indentation(ordered.group(1)) + ordered.group(2);
                    isList = true;
                } else if (unordered.matches()) {
                    line = indentation(unordered.group(1)) + "•  " + unordered.group(2);
                    isList = true;
                } else if (line.startsWith(">")) {
                    line = "│  " + line.substring(1).replaceFirst("^\\s+", "");
                    isQuote = true;
                }

                appendInline(result, line);
                int end = result.length();
                if (isQuote && end > start) {
                    result.setSpan(new ForegroundColorSpan(0xFF667085), start, start + 1,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                if (isHeading) {
                    result.setSpan(new StyleSpan(Typeface.BOLD), start, end,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    result.setSpan(new RelativeSizeSpan(headingScale(heading.group(1).length())),
                            start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                if (isList) {
                    result.setSpan(new LeadingMarginSpan.Standard(24, 8), start, end,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }
            result.append('\n');
            previousBlank = false;
        }

        while (result.length() > 0 && result.charAt(result.length() - 1) == '\n') {
            result.delete(result.length() - 1, result.length());
        }
        return result;
    }

    private static boolean isTableHeader(String header, String separator) {
        return header.contains("|") && TABLE_SEPARATOR.matcher(separator).matches();
    }

    private static void appendTable(SpannableStringBuilder result, String[] lines,
                                    int startLine, int endLine) {
        List<String[]> rows = new ArrayList<>();
        int columns = 0;
        rows.add(tableCells(lines[startLine]));
        for (int index = startLine + 2; index < endLine; index++) {
            String[] cells = tableCells(lines[index]);
            rows.add(cells);
            columns = Math.max(columns, cells.length);
        }
        columns = Math.max(columns, rows.get(0).length);
        int[] widths = new int[columns];
        for (String[] row : rows) {
            for (int column = 0; column < row.length; column++) {
                widths[column] = Math.min(MAX_TABLE_COLUMN_WIDTH,
                        Math.max(widths[column], row[column].length()));
            }
        }

        int start = result.length();
        appendTableRow(result, rows.get(0), widths);
        result.append('\n');
        for (int column = 0; column < columns; column++) {
            if (column > 0) result.append("-+-");
            appendRepeated(result, '-', widths[column]);
        }
        for (int row = 1; row < rows.size(); row++) {
            result.append('\n');
            appendTableRow(result, rows.get(row), widths);
        }
        result.setSpan(new TypefaceSpan("monospace"), start, result.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        result.setSpan(new BackgroundColorSpan(0xFFF0F1F3), start, result.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private static String[] tableCells(String line) {
        String normalized = line.trim();
        if (normalized.startsWith("|")) normalized = normalized.substring(1);
        if (normalized.endsWith("|")) normalized = normalized.substring(0, normalized.length() - 1);
        String[] source = normalized.split("\\|", -1);
        String[] result = new String[source.length];
        for (int index = 0; index < source.length; index++) {
            result[index] = source[index].trim()
                    .replaceAll("\\*\\*(.+?)\\*\\*", "$1")
                    .replaceAll("`([^`]+)`", "$1");
        }
        return result;
    }

    private static void appendTableRow(SpannableStringBuilder result, String[] cells, int[] widths) {
        for (int column = 0; column < widths.length; column++) {
            if (column > 0) result.append(" | ");
            String value = column < cells.length ? truncate(cells[column], widths[column]) : "";
            result.append(value);
            appendRepeated(result, ' ', widths[column] - value.length());
        }
    }

    private static String truncate(String value, int width) {
        if (value.length() <= width) return value;
        return value.substring(0, Math.max(0, width - 1)) + "…";
    }

    private static void appendRepeated(SpannableStringBuilder result, char value, int count) {
        for (int index = 0; index < count; index++) result.append(value);
    }

    private static String indentation(String spaces) {
        int level = Math.min(spaces.length() / 2, 4);
        StringBuilder indentation = new StringBuilder();
        for (int i = 0; i < level; i++) indentation.append("  ");
        return indentation.toString();
    }

    private static float headingScale(int level) {
        switch (level) {
            case 1: return 1.35f;
            case 2: return 1.25f;
            case 3: return 1.15f;
            default: return 1.08f;
        }
    }

    private static void appendInline(SpannableStringBuilder output, String line) {
        Matcher matcher = INLINE.matcher(line);
        int cursor = 0;
        while (matcher.find()) {
            output.append(line, cursor, matcher.start());
            int start = output.length();
            if (matcher.group(6) != null) {
                output.append(matcher.group(6));
                output.setSpan(new ForegroundColorSpan(0xFF175CD3), start, output.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                output.setSpan(new URLSpan(matcher.group(7)), start, output.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else {
                String content = firstGroup(matcher, 1, 5);
                output.append(content);
                Object span;
                if (matcher.group(1) != null || matcher.group(2) != null) {
                    span = new StyleSpan(Typeface.BOLD);
                } else if (matcher.group(3) != null || matcher.group(4) != null) {
                    span = new StyleSpan(Typeface.ITALIC);
                } else {
                    span = new TypefaceSpan("monospace");
                    output.setSpan(new BackgroundColorSpan(Color.rgb(240, 241, 243)),
                            start, output.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                output.setSpan(span, start, output.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            cursor = matcher.end();
        }
        output.append(line, cursor, line.length());
    }

    private static String firstGroup(Matcher matcher, int first, int last) {
        for (int group = first; group <= last; group++) {
            String value = matcher.group(group);
            if (value != null) return value;
        }
        return "";
    }
}
