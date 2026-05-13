package com.example.dbtoolbox.common;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;

public final class CsvUtils {

    private CsvUtils() {
    }

    public static void writeRow(Writer writer, List<?> values, CsvFormat format) throws IOException {
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                writer.write(format.getDelimiter());
            }
            Object value = values.get(i);
            writeValue(writer, value == null ? format.getNullToken() : String.valueOf(value), format);
        }
        writer.write(format.getLineSeparator());
    }

    public static List<String> parseLine(String line, CsvFormat format) {
        List<String> values = new ArrayList<String>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == format.getEscapeChar() && i + 1 < line.length() && line.charAt(i + 1) == format.getQuoteChar()) {
                    current.append(format.getQuoteChar());
                    i++;
                } else if (c == format.getQuoteChar()) {
                    quoted = false;
                } else {
                    current.append(c);
                }
            } else if (c == format.getQuoteChar()) {
                quoted = true;
            } else if (c == format.getDelimiter()) {
                values.add(nullIfToken(current.toString(), format));
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        values.add(nullIfToken(current.toString(), format));
        return values;
    }

    private static String nullIfToken(String value, CsvFormat format) {
        return format.getNullToken().equals(value) ? null : value;
    }

    private static void writeValue(Writer writer, String value, CsvFormat format) throws IOException {
        boolean quote = value.indexOf(format.getDelimiter()) >= 0
                || value.indexOf(format.getQuoteChar()) >= 0
                || value.indexOf('\n') >= 0
                || value.indexOf('\r') >= 0;
        if (!quote) {
            writer.write(value);
            return;
        }
        writer.write(format.getQuoteChar());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == format.getQuoteChar()) {
                writer.write(format.getEscapeChar());
            }
            writer.write(c);
        }
        writer.write(format.getQuoteChar());
    }
}
