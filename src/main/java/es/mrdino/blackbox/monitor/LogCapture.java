package es.mrdino.blackbox.monitor;

import es.mrdino.blackbox.storage.TelemetryStore;
import es.mrdino.blackbox.util.Csv;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

public final class LogCapture extends Handler implements AutoCloseable {
    private static final String HEADER = "timestamp,level,logger,message,thrown_type,stack";
    private final TelemetryStore store;
    private final int messageMaxChars;
    private final int stackMaxChars;
    private final Logger root = Logger.getLogger("");

    public LogCapture(TelemetryStore store, int messageMaxChars, int stackMaxChars) {
        this.store = store;
        this.messageMaxChars = messageMaxChars;
        this.stackMaxChars = stackMaxChars;
        setLevel(Level.WARNING);
        root.addHandler(this);
    }

    @Override
    public void publish(LogRecord record) {
        if (!isLoggable(record)) return;
        Instant instant = record.getInstant();
        Throwable thrown = record.getThrown();
        String stack = "";
        if (thrown != null) {
            StringWriter writer = new StringWriter();
            thrown.printStackTrace(new PrintWriter(writer));
            stack = shorten(writer.toString(), stackMaxChars);
        }
        store.append("warnings", HEADER, instant, Csv.row(instant.toEpochMilli(), record.getLevel().getName(),
                record.getLoggerName(), shorten(format(record), messageMaxChars),
                thrown == null ? "" : thrown.getClass().getName(), stack));
    }

    private static String format(LogRecord record) {
        String message = record.getMessage() == null ? "" : record.getMessage();
        Object[] parameters = record.getParameters();
        if (parameters == null || parameters.length == 0) return message;
        try { return java.text.MessageFormat.format(message, parameters); }
        catch (IllegalArgumentException ignored) { return message; }
    }

    private static String shorten(String value, int max) { return value.length() <= max ? value : value.substring(0, max); }
    @Override public void flush() {}
    @Override public void close() { root.removeHandler(this); }
}
