package com.example.dbtoolbox.common;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

public class CsvFormat {

    private char delimiter = ',';
    private char quoteChar = '"';
    private char escapeChar = '"';
    private String lineSeparator = "\n";
    private Charset charset = StandardCharsets.UTF_8;
    private String nullToken = "\\N";

    public static CsvFormat of(String delimiter, String quoteChar, String escapeChar, String lineSeparator, String charsetName) {
        CsvFormat format = new CsvFormat();
        format.setDelimiter(decodeChar(delimiter, format.getDelimiter()));
        format.setQuoteChar(decodeChar(quoteChar, format.getQuoteChar()));
        format.setEscapeChar(decodeChar(escapeChar, format.getEscapeChar()));
        if (lineSeparator != null && lineSeparator.length() > 0) {
            format.setLineSeparator(decodeLineSeparator(lineSeparator));
        }
        if (charsetName != null && charsetName.trim().length() > 0) {
            format.setCharset(Charset.forName(charsetName.trim()));
        }
        return format;
    }

    public char getDelimiter() {
        return delimiter;
    }

    public void setDelimiter(char delimiter) {
        this.delimiter = delimiter;
    }

    public char getQuoteChar() {
        return quoteChar;
    }

    public void setQuoteChar(char quoteChar) {
        this.quoteChar = quoteChar;
    }

    public char getEscapeChar() {
        return escapeChar;
    }

    public void setEscapeChar(char escapeChar) {
        this.escapeChar = escapeChar;
    }

    public String getLineSeparator() {
        return lineSeparator;
    }

    public void setLineSeparator(String lineSeparator) {
        this.lineSeparator = lineSeparator;
    }

    public Charset getCharset() {
        return charset;
    }

    public void setCharset(Charset charset) {
        this.charset = charset;
    }

    public String getNullToken() {
        return nullToken;
    }

    public void setNullToken(String nullToken) {
        this.nullToken = nullToken;
    }

    private static String decodeLineSeparator(String value) {
        if ("\\r\\n".equals(value)) {
            return "\r\n";
        }
        if ("\\n".equals(value)) {
            return "\n";
        }
        if ("\\r".equals(value)) {
            return "\r";
        }
        return value;
    }

    private static char decodeChar(String value, char defaultValue) {
        if (value == null || value.length() == 0) {
            return defaultValue;
        }
        if (value.length() == 1) {
            return value.charAt(0);
        }
        String trimmed = value.trim();
        if (trimmed.length() == 0) {
            return defaultValue;
        }
        if ("\\t".equalsIgnoreCase(trimmed) || "TAB".equalsIgnoreCase(trimmed)) {
            return '\t';
        }
        if ("\\n".equalsIgnoreCase(trimmed) || "LF".equalsIgnoreCase(trimmed)) {
            return '\n';
        }
        if ("\\r".equalsIgnoreCase(trimmed) || "CR".equalsIgnoreCase(trimmed)) {
            return '\r';
        }
        if (trimmed.matches("(?i)CHAR\\(\\d+\\)")) {
            int code = Integer.parseInt(trimmed.substring(trimmed.indexOf('(') + 1, trimmed.indexOf(')')));
            return (char) code;
        }
        if (trimmed.matches("(?i)0x[0-9a-f]+")) {
            return (char) Integer.parseInt(trimmed.substring(2), 16);
        }
        if (trimmed.matches("(?i)\\\\u[0-9a-f]{4}")) {
            return (char) Integer.parseInt(trimmed.substring(2), 16);
        }
        return trimmed.charAt(0);
    }
}
