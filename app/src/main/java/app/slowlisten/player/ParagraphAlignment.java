package app.slowlisten.player;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ParagraphAlignment {
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{Nd}]+(?:['’][\\p{L}\\p{Nd}]+)*");
    private static final String CACHE_VERSION = "1|";
    private static final int MAX_PARAGRAPH_WORDS = 65;
    private static final long MAX_GAP_MATRIX_CELLS = 500_000;

    private ParagraphAlignment() {}

    record Paragraph(int start, int end, int firstWord, int endWord) {}
    record Transcript(String text, List<String> words, List<Paragraph> paragraphs) {}
    record SpokenWord(String word, long startMs, long endMs) {}
    record Cue(int paragraph, long startMs, long endMs) {}
    private record Phrase(String first, String middle, String last) {}
    private record Anchor(int text, int speech) {}

    static Transcript parse(String text) {
        List<String> words = new ArrayList<>();
        List<Paragraph> paragraphs = new ArrayList<>();
        StringBuilder display = new StringBuilder();
        String[] blocks = text.strip().split("\\R\\h*\\R(?:\\h*\\R)*");
        if (blocks.length == 1) {
            blocks = blocks[0].split("\\R");
        }
        for (String block : blocks) {
            appendBlock(block.strip(), display, words, paragraphs);
        }
        return new Transcript(display.toString(), words, paragraphs);
    }

    private static void appendBlock(String block, StringBuilder display, List<String> words,
                                    List<Paragraph> paragraphs) {
        if (block.isEmpty()) {
            return;
        }
        List<Integer> wordStarts = new ArrayList<>();
        Matcher matcher = WORD.matcher(block);
        while (matcher.find()) {
            wordStarts.add(matcher.start());
        }
        BreakIterator sentences = BreakIterator.getSentenceInstance(Locale.ENGLISH);
        sentences.setText(block);
        int first = 0;
        int start = 0;
        while (wordStarts.size() - first > MAX_PARAGRAPH_WORDS) {
            int limit = first + MAX_PARAGRAPH_WORDS;
            int end = wordStarts.get(limit);
            int sentence = sentences.preceding(end + 1);
            if (sentence > wordStarts.get(first + MAX_PARAGRAPH_WORDS / 2)) {
                end = sentence;
                while (limit > first && wordStarts.get(limit - 1) >= end) {
                    limit--;
                }
            }
            appendParagraph(block.substring(start, end).strip(), display, words, paragraphs);
            start = end;
            first = limit;
        }
        appendParagraph(block.substring(start).strip(), display, words, paragraphs);
    }

    private static void appendParagraph(String text, StringBuilder display, List<String> words,
                                        List<Paragraph> paragraphs) {
        if (text.isEmpty()) {
            return;
        }
        if (display.length() > 0) {
            display.append("\n\n");
        }
        int start = display.length();
        int firstWord = words.size();
        display.append(text);
        Matcher matcher = WORD.matcher(text);
        while (matcher.find()) {
            words.add(normalize(matcher.group()));
        }
        paragraphs.add(new Paragraph(start, display.length(), firstWord, words.size()));
    }

    static String normalize(String word) {
        return word.toLowerCase(Locale.ROOT).replace("'", "").replace("’", "");
    }

    static List<Cue> findCues(Transcript transcript, List<SpokenWord> speech) {
        List<String> text = transcript.words();
        if (text.isEmpty() || speech.isEmpty()) {
            return List.of();
        }
        List<String> spoken = new ArrayList<>(speech.size());
        for (SpokenWord word : speech) {
            spoken.add(normalize(word.word()));
        }

        int[] matches = new int[text.size()];
        Arrays.fill(matches, -1);
        int textStart = 0;
        int speechStart = 0;
        for (Anchor anchor : orderedAnchors(text, spoken)) {
            matchGap(text, spoken, textStart, anchor.text(), speechStart, anchor.speech(), matches);
            matches[anchor.text()] = anchor.speech();
            textStart = anchor.text() + 1;
            speechStart = anchor.speech() + 1;
        }
        matchGap(text, spoken, textStart, text.size(), speechStart, spoken.size(), matches);

        List<Cue> cues = new ArrayList<>();
        long previousEnd = 0;
        for (int index = 0; index < transcript.paragraphs().size(); index++) {
            Paragraph paragraph = transcript.paragraphs().get(index);
            int count = 0;
            int firstMatch = -1;
            int lastMatch = -1;
            for (int word = paragraph.firstWord(); word < paragraph.endWord(); word++) {
                if (matches[word] >= 0) {
                    if (firstMatch < 0) {
                        firstMatch = matches[word];
                    }
                    lastMatch = matches[word];
                    count++;
                }
            }
            int words = paragraph.endWord() - paragraph.firstWord();
            int minimum = Math.min(words, Math.max(3, (words + 1) / 2));
            // Sparse matches across an unrelated span do not make a timed paragraph.
            if (count == 0 || count < minimum || lastMatch - firstMatch + 1 > count * 3) {
                continue;
            }
            long start = Math.max(previousEnd, speech.get(firstMatch).startMs());
            long end = speech.get(lastMatch).endMs();
            if (end > start) {
                cues.add(new Cue(index, start, end));
                previousEnd = end;
            }
        }
        return cues;
    }

    private static Map<Phrase, Integer> phrasePositions(List<String> words) {
        Map<Phrase, Integer> positions = new HashMap<>();
        for (int index = 1; index + 1 < words.size(); index++) {
            Phrase phrase = new Phrase(words.get(index - 1), words.get(index), words.get(index + 1));
            positions.merge(phrase, index, (old, duplicate) -> -1);
        }
        return positions;
    }

    private static List<Anchor> orderedAnchors(List<String> text, List<String> speech) {
        Map<Phrase, Integer> textPositions = phrasePositions(text);
        Map<Phrase, Integer> speechPositions = phrasePositions(speech);
        List<Anchor> candidates = new ArrayList<>();
        for (Map.Entry<Phrase, Integer> entry : textPositions.entrySet()) {
            int spoken = speechPositions.getOrDefault(entry.getKey(), -1);
            if (entry.getValue() >= 0 && spoken >= 0) {
                candidates.add(new Anchor(entry.getValue(), spoken));
            }
        }
        candidates.sort((left, right) -> Integer.compare(left.text(), right.text()));

        // Use the longest ordered sequence of unambiguous three-word anchors.
        int length = 0;
        int[] tails = new int[candidates.size()];
        int[] previous = new int[candidates.size()];
        for (int index = 0; index < candidates.size(); index++) {
            int spoken = candidates.get(index).speech();
            int low = 0;
            int high = length;
            while (low < high) {
                int middle = (low + high) >>> 1;
                if (candidates.get(tails[middle]).speech() < spoken) {
                    low = middle + 1;
                } else {
                    high = middle;
                }
            }
            previous[index] = low == 0 ? -1 : tails[low - 1];
            tails[low] = index;
            length = Math.max(length, low + 1);
        }
        Anchor[] ordered = new Anchor[length];
        int tail = length == 0 ? -1 : tails[length - 1];
        for (int index = length - 1; index >= 0; index--) {
            ordered[index] = candidates.get(tail);
            tail = previous[tail];
        }
        return Arrays.asList(ordered);
    }

    private static void matchGap(List<String> text, List<String> speech, int textStart, int textEnd,
                                 int speechStart, int speechEnd, int[] matches) {
        int textCount = textEnd - textStart;
        int speechCount = speechEnd - speechStart;
        if (textCount == 0 || speechCount == 0) {
            return;
        }
        if (textCount == speechCount
                && text.subList(textStart, textEnd).equals(speech.subList(speechStart, speechEnd))) {
            for (int offset = 0; offset < textCount; offset++) {
                matches[textStart + offset] = speechStart + offset;
            }
            return;
        }
        if ((long) (textCount + 1) * (speechCount + 1) > MAX_GAP_MATRIX_CELLS) {
            return;
        }
        // Recover matches between anchors without allocating a matrix for a long recording.
        int stride = speechCount + 1;
        int[] lengths = new int[(textCount + 1) * stride];
        for (int t = textCount - 1; t >= 0; t--) {
            for (int s = speechCount - 1; s >= 0; s--) {
                int cell = t * stride + s;
                lengths[cell] = text.get(textStart + t).equals(speech.get(speechStart + s))
                        ? 1 + lengths[cell + stride + 1]
                        : Math.max(lengths[cell + stride], lengths[cell + 1]);
            }
        }
        int t = 0;
        int s = 0;
        while (t < textCount && s < speechCount) {
            if (text.get(textStart + t).equals(speech.get(speechStart + s))) {
                matches[textStart + t++] = speechStart + s++;
            } else if (lengths[(t + 1) * stride + s] >= lengths[t * stride + s + 1]) {
                t++;
            } else {
                s++;
            }
        }
    }

    static int paragraphAt(List<Cue> cues, long positionMs) {
        int low = 0;
        int high = cues.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (cues.get(middle).startMs() <= positionMs) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        if (low == 0) {
            return -1;
        }
        Cue cue = cues.get(low - 1);
        return positionMs < cue.endMs() + 350 ? cue.paragraph() : -1;
    }

    static String encode(List<Cue> cues) {
        StringBuilder data = new StringBuilder(CACHE_VERSION);
        for (Cue cue : cues) {
            data.append(cue.paragraph()).append(',').append(cue.startMs()).append(',')
                    .append(cue.endMs()).append(';');
        }
        return data.toString();
    }

    static List<Cue> decode(String data, int paragraphCount) {
        if (data == null || !data.startsWith(CACHE_VERSION)) {
            return null;
        }
        if (data.equals(CACHE_VERSION)) {
            return List.of();
        }
        List<Cue> cues = new ArrayList<>();
        long previousEnd = 0;
        int previousParagraph = -1;
        try {
            for (String line : data.substring(CACHE_VERSION.length()).split(";")) {
                String[] values = line.split(",");
                if (values.length != 3) {
                    return null;
                }
                int paragraph = Integer.parseInt(values[0]);
                long start = Long.parseLong(values[1]);
                long end = Long.parseLong(values[2]);
                if (paragraph <= previousParagraph || paragraph >= paragraphCount
                        || start < previousEnd || end <= start) {
                    return null;
                }
                cues.add(new Cue(paragraph, start, end));
                previousParagraph = paragraph;
                previousEnd = end;
            }
        } catch (NumberFormatException error) {
            return null;
        }
        return cues;
    }
}
