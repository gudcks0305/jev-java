package io.github.gudcks0305.jev.examples;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.core.JsonParser;
import io.github.gudcks0305.jev.Evaluation;
import io.github.gudcks0305.jev.JevClient;
import io.github.gudcks0305.jev.JevException;
import io.github.gudcks0305.jev.NoulQuestion;
import io.github.gudcks0305.jev.cloudflare.CloudflareJevClient;
import io.github.gudcks0305.jev.openrouter.OpenRouterJevClient;
import io.github.gudcks0305.jev.typesafe.TypeSafeJevClient;
import io.github.gudcks0305.jev.vercel.VercelJevClient;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Small labeled Noul evaluation example, separate from runtime inference. */
public final class LabeledEvaluationExample {
    private static final ObjectMapper JSON = new ObjectMapper();
    static {
        JSON.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        JSON.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    }
    private static final NoulQuestion QUESTION = NoulQuestion.of("refund", "Does the message request a refund?");
    private static final double[] THRESHOLDS = {0.5, 0.7, 0.9};
    private static final String SAMPLE = "/evaluation/refund-synthetic.jsonl";

    private LabeledEvaluationExample() {}

    record Row(String state, boolean label, Double offlineProbability) {}
    record Prediction(boolean label, Double probability, String errorKind) {}
    record Metrics(int total, int successful, int errors, int abstentions, int accepted,
                   int correct, double coverage, Double acceptedAccuracy) {}

    public static void main(String[] args) throws IOException {
        boolean live = false;
        String provider = "typesafe";
        Path file = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--live" -> {
                    if (live) throw new IllegalArgumentException("--live specified twice");
                    live = true;
                    if (i + 1 < args.length && !args[i + 1].startsWith("--")) provider = args[++i];
                }
                case "--file" -> {
                    if (file != null || i + 1 >= args.length) throw new IllegalArgumentException("--file requires one path");
                    file = Path.of(args[++i]);
                }
                default -> throw new IllegalArgumentException("Usage: [--file path.jsonl] [--live [typesafe|openrouter|vercel|cloudflare]]");
            }
        }

        List<Row> rows;
        if (file == null) {
            try (InputStream stream = LabeledEvaluationExample.class.getResourceAsStream(SAMPLE)) {
                if (stream == null) throw new IllegalStateException("Missing synthetic sample");
                rows = readRows(new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8)), live);
            }
        } else {
            try (BufferedReader reader = Files.newBufferedReader(file)) {
                rows = readRows(reader, live);
            }
        }

        List<Prediction> predictions;
        if (live) {
            try (JevClient client = client(provider)) {
                predictions = predictLive(rows, client);
            }
        } else {
            predictions = rows.stream().map(row -> new Prediction(row.label(), row.offlineProbability(), null)).toList();
        }

        System.out.printf("mode=%s rows=%d task=refund-request sample=%s%n",
                live ? "live:" + provider : "offline-synthetic", rows.size(), file == null ? "bundled-synthetic" : "user-file");
        System.out.println("threshold successful errors abstentions accepted correct coverage accepted_accuracy");
        for (double threshold : THRESHOLDS) {
            Metrics m = metrics(predictions, threshold);
            System.out.printf(Locale.ROOT, "%.1f %d %d %d %d %d %.3f %s%n", threshold,
                    m.successful(), m.errors(), m.abstentions(), m.accepted(), m.correct(), m.coverage(),
                    m.acceptedAccuracy() == null ? "N/A" : String.format(Locale.ROOT, "%.3f", m.acceptedAccuracy()));
        }
        long failures = predictions.stream().filter(p -> p.errorKind() != null).count();
        if (failures > 0) {
            Map<String, Long> kinds = predictions.stream().filter(p -> p.errorKind() != null)
                    .collect(java.util.stream.Collectors.groupingBy(Prediction::errorKind, java.util.TreeMap::new,
                            java.util.stream.Collectors.counting()));
            System.out.println("error_kinds=" + kinds);
        }
        System.out.println("accepted_accuracy=correct/accepted; coverage=accepted/total (includes failures). "
                + "At 0.5, p=0.5 predicts positive. Offline values are synthetic, not model accuracy.");
    }

    static List<Row> readRows(BufferedReader reader, boolean live) throws IOException {
        List<Row> rows = new ArrayList<>();
        String line;
        int number = 0;
        while ((line = reader.readLine()) != null) {
            number++;
            if (line.isBlank()) throw invalid(number, "blank line");
            JsonNode node;
            try {
                node = JSON.readTree(line);
            } catch (IOException ex) {
                throw invalid(number, "invalid JSON");
            }
            if (node == null || !node.isObject()) throw invalid(number, "expected object");
            var names = node.fieldNames();
            while (names.hasNext()) {
                if (!Set.of("state", "label", "offline_probability").contains(names.next()))
                    throw invalid(number, "unknown field");
            }
            JsonNode state = node.get("state");
            JsonNode label = node.get("label");
            JsonNode probability = node.get("offline_probability");
            if (state == null || !state.isTextual() || state.textValue().isBlank()) throw invalid(number, "state must be nonempty text");
            if (label == null || !label.isBoolean()) throw invalid(number, "label must be boolean");
            if (!live && probability == null) throw invalid(number, "offline_probability required offline");
            Double p = null;
            if (probability != null) {
                if (!probability.isNumber()) throw invalid(number, "offline_probability must be a number");
                p = probability.doubleValue();
                if (!Double.isFinite(p) || p < 0 || p > 1) throw invalid(number, "offline_probability outside [0,1]");
            }
            rows.add(new Row(state.textValue(), label.booleanValue(), p));
        }
        if (rows.isEmpty()) throw new IllegalArgumentException("Dataset has no rows");
        return List.copyOf(rows);
    }

    private static IllegalArgumentException invalid(int line, String reason) {
        return new IllegalArgumentException("Dataset line " + line + ": " + reason);
    }

    static List<Prediction> predictLive(List<Row> rows, JevClient client) {
        List<Prediction> predictions = new ArrayList<>();
        for (Row row : rows) {
            try {
                // Only state reaches the provider. The gold label stays local.
                Evaluation result = client.evaluate(row.state(), QUESTION);
                double p = Objects.requireNonNull(result.answer(QUESTION)).probability();
                if (!Double.isFinite(p) || p < 0 || p > 1) throw new IllegalArgumentException("Invalid probability");
                predictions.add(new Prediction(row.label(), p, null));
            } catch (RuntimeException ex) {
                String kind = ex instanceof JevException jev ? jev.kind().name() : "OTHER";
                predictions.add(new Prediction(row.label(), null, kind));
            }
        }
        return List.copyOf(predictions);
    }

    static Metrics metrics(List<Prediction> predictions, double threshold) {
        if (!Double.isFinite(threshold) || threshold < 0.5 || threshold > 1)
            throw new IllegalArgumentException("Threshold must be in [0.5,1]");
        int successful = 0, errors = 0, abstentions = 0, accepted = 0, correct = 0;
        for (Prediction item : predictions) {
            if (item.errorKind() != null) { errors++; continue; }
            double p = Objects.requireNonNull(item.probability());
            if (!Double.isFinite(p) || p < 0 || p > 1) throw new IllegalArgumentException("Invalid probability");
            successful++;
            // Positive takes the p=0.5 tie when threshold is 0.5.
            Boolean decision = null;
            if (p >= threshold) decision = Boolean.TRUE;
            else if (1 - p >= threshold) decision = Boolean.FALSE;
            if (decision == null) abstentions++;
            else {
                accepted++;
                if (decision == item.label()) correct++;
            }
        }
        int total = predictions.size();
        return new Metrics(total, successful, errors, abstentions, accepted, correct,
                total == 0 ? 0 : (double) accepted / total,
                accepted == 0 ? null : (double) correct / accepted);
    }

    private static JevClient client(String provider) {
        return switch (provider) {
            case "typesafe" -> TypeSafeJevClient.builder().build();
            case "openrouter" -> OpenRouterJevClient.builder().build();
            case "vercel" -> VercelJevClient.builder().build();
            case "cloudflare" -> CloudflareJevClient.builder().build();
            default -> throw new IllegalArgumentException("Unknown provider: " + provider);
        };
    }
}
