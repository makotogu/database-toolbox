package com.example.dbtoolbox.common;

import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CsvUtilsTest {

    @Test
    void writesAndParsesCustomDelimiter() throws Exception {
        CsvFormat format = CsvFormat.of("|", "\"", "\"", "\\n", "UTF-8");
        StringWriter writer = new StringWriter();

        CsvUtils.writeRow(writer, Arrays.<Object>asList("1", "a|b", "quote\"value", null), format);

        String line = writer.toString().trim();
        assertEquals("1|\"a|b\"|\"quote\"\"value\"|\\N", line);

        List<String> parsed = CsvUtils.parseLine(line, format);
        assertEquals("1", parsed.get(0));
        assertEquals("a|b", parsed.get(1));
        assertEquals("quote\"value", parsed.get(2));
        assertEquals(null, parsed.get(3));
    }

    @Test
    void supportsChar27DelimiterToken() throws Exception {
        CsvFormat format = CsvFormat.of("CHAR(27)", "\"", "\"", "\\n", "UTF-8");
        StringWriter writer = new StringWriter();

        CsvUtils.writeRow(writer, Arrays.<Object>asList("1", "alice", "ACTIVE"), format);

        String line = writer.toString().trim();
        assertEquals("1" + ((char) 27) + "alice" + ((char) 27) + "ACTIVE", line);
        assertEquals("alice", CsvUtils.parseLine(line, format).get(1));
    }
}
