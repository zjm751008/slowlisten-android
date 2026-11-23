package app.slowlisten.player;

import android.content.Context;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.vosk.LibVosk;
import org.vosk.LogLevel;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

final class SpeechAlignment {
    private static final String MODEL = "vosk-model-small-en-us-0.15";
    private static final int SAMPLE_RATE = 16_000;
    private static final long CODEC_TIMEOUT_US = 10_000;
    private static final long MAX_STALL_NS = TimeUnit.SECONDS.toNanos(30);

    private SpeechAlignment() {}

    static List<ParagraphAlignment.Cue> align(Context context, RecordingStore store,
            RecordingStore.Recording recording, ParagraphAlignment.Transcript transcript,
            IntConsumer reportProgress) throws IOException, InterruptedException {
        if (transcript.words().isEmpty()) {
            return List.of();
        }
        Path directory = prepareModel(context);
        LibVosk.setLogLevel(LogLevel.WARNINGS);
        List<ParagraphAlignment.SpokenWord> words;
        try (Model model = new Model(directory.toString());
             Recognizer recognizer = new Recognizer(model, SAMPLE_RATE)) {
            recognizer.setWords(true);
            Recognition recognition = new Recognition(recognizer, recording.duration(), reportProgress);
            decode(store, recording, recognition);
            words = recognition.finish();
        }
        checkInterrupted();
        return ParagraphAlignment.findCues(transcript, words);
    }

    private static Path prepareModel(Context context) throws IOException, InterruptedException {
        Path parent = context.getNoBackupFilesDir().toPath();
        Path model = parent.resolve(MODEL);
        if (Files.isDirectory(model)) {
            return model;
        }
        Path staging = Files.createTempDirectory(parent, ".speech-model-");
        try (ZipInputStream zip = new ZipInputStream(context.getAssets().open(MODEL + ".zip"))) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                checkInterrupted();
                Path target = staging.resolve(entry.getName()).normalize();
                if (!target.startsWith(staging.resolve(MODEL))) {
                    throw new IOException("Invalid speech model entry");
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(zip, target);
                }
                zip.closeEntry();
            }
            checkInterrupted();
            return Files.move(staging.resolve(MODEL), model);
        } finally {
            try (Stream<Path> files = Files.walk(staging)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).collect(Collectors.toList())) {
                    Files.deleteIfExists(file);
                }
            }
        }
    }

    private static void decode(RecordingStore store, RecordingStore.Recording recording,
                               Recognition recognition) throws IOException, InterruptedException {
        MediaExtractor extractor = new MediaExtractor();
        try {
            store.openAudio(recording, extractor);
            for (int track = 0; track < extractor.getTrackCount(); track++) {
                MediaFormat format = extractor.getTrackFormat(track);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime == null || !mime.startsWith("audio/")) {
                    continue;
                }
                extractor.selectTrack(track);
                if ("audio/raw".equals(mime)) {
                    readPcm(extractor, new PcmStream(format, recognition));
                } else {
                    decodeCompressed(extractor, mime, format, recognition);
                }
                return;
            }
            throw new IOException("No audio stream");
        } finally {
            extractor.release();
        }
    }

    private static void readPcm(MediaExtractor extractor, PcmStream stream)
            throws IOException, InterruptedException {
        ByteBuffer input = ByteBuffer.allocate(64 * 1024);
        for (long size; (size = extractor.getSampleSize()) >= 0; extractor.advance()) {
            checkInterrupted();
            if (size > Integer.MAX_VALUE) {
                throw new IOException("PCM sample is too large");
            }
            if (size > input.capacity()) {
                input = ByteBuffer.allocate((int) size);
            }
            input.clear();
            int count = extractor.readSampleData(input, 0);
            if (count < 0) {
                return;
            }
            input.position(0);
            input.limit(count);
            stream.write(input);
        }
    }

    private static void decodeCompressed(MediaExtractor extractor, String mime, MediaFormat format,
                                         Recognition recognition)
            throws IOException, InterruptedException {
        MediaCodec decoder = MediaCodec.createDecoderByType(mime);
        try {
            decoder.configure(format, null, null, 0);
            decoder.start();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            PcmStream pcm = null;
            boolean inputEnded = false;
            long lastProgress = System.nanoTime();
            while (true) {
                checkInterrupted();
                if (!inputEnded) {
                    int index = decoder.dequeueInputBuffer(CODEC_TIMEOUT_US);
                    if (index >= 0) {
                        ByteBuffer input = decoder.getInputBuffer(index);
                        if (input == null) {
                            throw new IOException("Missing decoder input buffer");
                        }
                        int size = extractor.readSampleData(input, 0);
                        inputEnded = size < 0;
                        if (inputEnded) {
                            decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        } else {
                            decoder.queueInputBuffer(index, 0, size, extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                        lastProgress = System.nanoTime();
                    }
                }

                int index = decoder.dequeueOutputBuffer(info, CODEC_TIMEOUT_US);
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    pcm = new PcmStream(decoder.getOutputFormat(), recognition);
                    lastProgress = System.nanoTime();
                } else if (index >= 0) {
                    try {
                        ByteBuffer output = decoder.getOutputBuffer(index);
                        if (output != null && info.size > 0
                                && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            if (pcm == null) {
                                pcm = new PcmStream(decoder.getOutputFormat(index), recognition);
                            }
                            output.position(info.offset);
                            output.limit(info.offset + info.size);
                            pcm.write(output);
                        }
                        if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            return;
                        }
                        lastProgress = System.nanoTime();
                    } finally {
                        decoder.releaseOutputBuffer(index, false);
                    }
                } else if (System.nanoTime() - lastProgress > MAX_STALL_NS) {
                    throw new IOException("Audio decoding stalled");
                }
            }
        } finally {
            decoder.release();
        }
    }

    private static void checkInterrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException();
        }
    }

    private static final class PcmStream {
        private final Recognition recognition;
        private final int channels;
        private final int encoding;
        private final int frameBytes;
        private final double sourceFramesPerSample;
        private long frame;
        private double nextSample;
        private float previous;

        PcmStream(MediaFormat format, Recognition recognition) throws IOException {
            this.recognition = recognition;
            channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT, 0);
            encoding = format.getInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT);
            int sampleBytes = switch (encoding) {
                case AudioFormat.ENCODING_PCM_8BIT -> 1;
                case AudioFormat.ENCODING_PCM_16BIT -> 2;
                case AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3;
                case AudioFormat.ENCODING_PCM_32BIT, AudioFormat.ENCODING_PCM_FLOAT -> 4;
                default -> throw new IOException("Unsupported PCM encoding: " + encoding);
            };
            int rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE, 0);
            if (rate <= 0 || channels <= 0) {
                throw new IOException("Invalid PCM format");
            }
            frameBytes = channels * sampleBytes;
            sourceFramesPerSample = (double) rate / SAMPLE_RATE;
        }

        void write(ByteBuffer bytes) throws IOException, InterruptedException {
            bytes.order(ByteOrder.LITTLE_ENDIAN);
            while (bytes.remaining() >= frameBytes) {
                float current = 0;
                for (int channel = 0; channel < channels; channel++) {
                    current += readSample(bytes);
                }
                current /= channels;
                while (nextSample <= frame) {
                    float blend = (float) (nextSample - (frame - 1));
                    float sample = frame == 0 ? current : previous + (current - previous) * blend;
                    recognition.add((short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, sample)));
                    nextSample += sourceFramesPerSample;
                }
                previous = current;
                frame++;
            }
        }

        private float readSample(ByteBuffer bytes) {
            return switch (encoding) {
                case AudioFormat.ENCODING_PCM_8BIT -> ((bytes.get() & 0xff) - 128) * 256;
                case AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
                    bytes.get();
                    int low = bytes.get() & 0xff;
                    yield (bytes.get() << 8) | low;
                }
                case AudioFormat.ENCODING_PCM_32BIT -> bytes.getInt() >> 16;
                case AudioFormat.ENCODING_PCM_FLOAT -> bytes.getFloat() * 32768f;
                default -> bytes.getShort();
            };
        }
    }

    private static final class Recognition {
        private final Recognizer recognizer;
        private final long durationMs;
        private final IntConsumer progress;
        private final short[] pending = new short[SAMPLE_RATE / 4];
        private final List<ParagraphAlignment.SpokenWord> words = new ArrayList<>();
        private int size;
        private long samples;
        private int previousProgress = -1;

        Recognition(Recognizer recognizer, long durationMs, IntConsumer progress) {
            this.recognizer = recognizer;
            this.durationMs = durationMs;
            this.progress = progress;
        }

        void add(short sample) throws IOException, InterruptedException {
            pending[size++] = sample;
            if (size == pending.length) {
                flush();
            }
        }

        private void flush() throws IOException, InterruptedException {
            checkInterrupted();
            if (size == 0) {
                return;
            }
            if (recognizer.acceptWaveForm(pending, size)) {
                collect(recognizer.getResult());
            }
            samples += size;
            size = 0;
            if (durationMs > 0) {
                int percentage = (int) Math.min(99, samples * 100_000 / SAMPLE_RATE / durationMs);
                if (percentage != previousProgress) {
                    progress.accept(percentage);
                    previousProgress = percentage;
                }
            }
        }

        List<ParagraphAlignment.SpokenWord> finish() throws IOException, InterruptedException {
            flush();
            collect(recognizer.getFinalResult());
            return words;
        }

        private void collect(String result) throws IOException {
            try {
                JSONArray recognized = new JSONObject(result).optJSONArray("result");
                if (recognized == null) {
                    return;
                }
                for (int i = 0; i < recognized.length(); i++) {
                    JSONObject token = recognized.getJSONObject(i);
                    String word = token.getString("word");
                    long start = Math.round(token.getDouble("start") * 1_000);
                    long end = Math.round(token.getDouble("end") * 1_000);
                    if (!word.isBlank() && start >= 0 && end > start) {
                        words.add(new ParagraphAlignment.SpokenWord(word, start, end));
                    }
                }
            } catch (JSONException error) {
                throw new IOException("Invalid speech recognition result", error);
            }
        }
    }
}
