package com.ourgiant.saml.gui;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Usage tips bundled in the jar (#211), from {@code tips/usage-tips.txt}: tips separated by
 * {@code ---} lines, each a title line followed by its body (simple HTML), {@code #} lines ignored.
 */
final class UsageTips {
    private static final Logger logger = LoggerFactory.getLogger(UsageTips.class);
    private static final String RESOURCE = "/tips/usage-tips.txt";

    private UsageTips() {
    }

    record Tip(String title, String bodyHtml) {
    }

    static List<Tip> load() {
        try (InputStream in = UsageTips.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                logger.warn("Usage tips resource {} is missing", RESOURCE);
                return List.of();
            }
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            logger.warn("Could not read usage tips", e);
            return List.of();
        }
    }

    static List<Tip> parse(String text) {
        List<Tip> tips = new ArrayList<>();
        List<String> block = new ArrayList<>();
        for (String line : (text + "\n---").split("\\R")) {
            if (line.trim().equals("---")) {
                addTip(tips, block);
                block.clear();
            } else if (!line.startsWith("#")) {
                block.add(line);
            }
        }
        return List.copyOf(tips);
    }

    private static void addTip(List<Tip> tips, List<String> block) {
        List<String> lines = block.stream().map(String::trim).filter(l -> !l.isEmpty()).toList();
        if (lines.size() >= 2) {
            tips.add(new Tip(lines.get(0), String.join(" ", lines.subList(1, lines.size()))));
        }
    }

    /** The tip index to start on, wrapping a stored "next" index into range (e.g. after tips were removed). */
    static int startIndex(int storedNext, int tipCount) {
        return tipCount == 0 ? 0 : Math.floorMod(storedNext, tipCount);
    }
}
