package com.ashfaq.gcplab._09_ai_commerce_search.toystore;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Our own copy of every event we send to the Retail API, one line per event in
 * a tab-separated file. The Retail API cannot list events back, so this file is
 * where shopper history can be read. The web backend and the simulator both
 * write to it, so the UI shows events from either.
 */
@Component
public class ToyEventLog {

    private static final Path FILE = Path.of("search-experiments", "toy-events.tsv");

    public record Entry(String time, String visitorId, String eventType, String productId, String searchQuery) {
    }

    public synchronized void append(String visitorId, String eventType, String productId, String searchQuery) {
        String line = String.join("\t",
                Instant.now().toString(),
                clean(visitorId),
                clean(eventType),
                clean(productId),
                clean(searchQuery)) + System.lineSeparator();
        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException("could not write " + FILE, e);
        }
    }

    /** Newest first. An empty visitorId returns every shopper. */
    public synchronized List<Entry> read(String visitorId, int limit) {
        if (!Files.exists(FILE)) {
            return List.of();
        }
        List<Entry> entries = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(FILE, StandardCharsets.UTF_8)) {
                String[] parts = line.split("\t", -1);
                if (parts.length < 5) {
                    continue;
                }
                if (visitorId == null || visitorId.isBlank() || visitorId.equals(parts[1])) {
                    entries.add(new Entry(parts[0], parts[1], parts[2], parts[3], parts[4]));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + FILE, e);
        }
        Collections.reverse(entries);
        return entries.size() > limit ? List.copyOf(entries.subList(0, limit)) : List.copyOf(entries);
    }

    private static String clean(String value) {
        return value == null ? "" : value.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }
}
