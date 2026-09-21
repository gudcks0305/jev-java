package io.github.gudcks0305.jev.schema;

import io.github.gudcks0305.jev.Answer;
import io.github.gudcks0305.jev.ChoiceAnswer;
import io.github.gudcks0305.jev.ChoiceQuestion;
import io.github.gudcks0305.jev.Evaluation;
import io.github.gudcks0305.jev.JevException;
import io.github.gudcks0305.jev.NoulAnswer;
import io.github.gudcks0305.jev.NoulQuestion;
import io.github.gudcks0305.jev.Question;
import io.github.gudcks0305.jev.ScoreAnswer;
import io.github.gudcks0305.jev.ScoreQuestion;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Compiles annotated Java records into immutable, reusable batches of Jev questions.
 * Nested records are flattened into dotted field paths. Schema errors fail before HTTP.
 * This is a closed-answer mapper, not general JSON generation or a tool executor.
 */
public final class JevSchema<T> {
    private static final String NONE = "<none>"; // Not a legal Java enum constant name.
    private static final ClassValue<JevSchema<?>> SCHEMAS = new ClassValue<>() {
        @Override protected JevSchema<?> computeValue(Class<?> type) { return new JevSchema<>(type); }
    };

    private final Class<T> recordType;
    private final List<Question<?>> questions;
    private final Reader reader;

    private JevSchema(Class<T> recordType) {
        this.recordType = recordType;
        Compilation compilation = new Compilation();
        this.reader = compileRecord(recordType, "", compilation);
        this.questions = List.copyOf(compilation.questions);
    }

    /** Compiles once per record class. ClassValue permits class-loader unloading. */
    @SuppressWarnings("unchecked")
    public static <T> JevSchema<T> of(Class<T> recordType) {
        return (JevSchema<T>) SCHEMAS.get(Objects.requireNonNull(recordType, "recordType"));
    }

    public Class<T> recordType() { return recordType; }
    public List<Question<?>> questions() { return questions; }

    /** Maps an evaluation produced with this schema's question instances. */
    public TypedEvaluation<T> decode(Evaluation evaluation) {
        Objects.requireNonNull(evaluation, "evaluation");
        // Preserve the native API's identity check instead of trusting coincidentally equal ids.
        for (Question<?> question : questions) evaluation.answer(question);
        return new TypedEvaluation<>(recordType.cast(reader.read(evaluation)), evaluation);
    }

    private static Reader compileRecord(Class<?> type, String prefix, Compilation c) {
        if (!type.isRecord()) throw invalid(prefix, "Output type must be a Java record");
        if (type.getTypeParameters().length != 0) throw invalid(prefix, "Generic record classes are not supported");
        if (!c.visiting.add(type)) throw invalid(prefix, "Recursive record schemas are not supported");
        try {
            RecordComponent[] components = type.getRecordComponents();
            if (components.length == 0) throw invalid(prefix, "A record must contain at least one judgment field");
            List<Reader> fields = new ArrayList<>();
            Class<?>[] parameterTypes = new Class<?>[components.length];
            for (int i = 0; i < components.length; i++) {
                RecordComponent component = components[i];
                parameterTypes[i] = component.getType();
                String path = prefix.isEmpty() ? component.getName() : prefix + "." + component.getName();
                fields.add(compileField(component, path, c));
            }
            Constructor<?> constructor;
            try {
                constructor = type.getDeclaredConstructor(parameterTypes);
                if (!constructor.trySetAccessible()) throw invalid(prefix, "Canonical record constructor is inaccessible; use a public record or open its package");
            } catch (ReflectiveOperationException | SecurityException ex) {
                throw invalid(prefix, "Cannot access canonical record constructor");
            }
            return evaluation -> {
                Object[] arguments = new Object[fields.size()];
                for (int i = 0; i < fields.size(); i++) arguments[i] = fields.get(i).read(evaluation);
                try {
                    return constructor.newInstance(arguments);
                } catch (InvocationTargetException ex) {
                    // A user compact constructor may reject even structurally valid model output.
                    throw protocol(prefix, "Record constructor rejected the mapped result");
                } catch (ReflectiveOperationException | IllegalArgumentException ex) {
                    throw protocol(prefix, "Cannot construct mapped record");
                }
            };
        } finally {
            c.visiting.remove(type);
        }
    }

    private static Reader compileField(RecordComponent field, String path, Compilation c) {
        JevBoolean bool = field.getAnnotation(JevBoolean.class);
        JevProbability probability = field.getAnnotation(JevProbability.class);
        JevChoice choice = field.getAnnotation(JevChoice.class);
        JevScore score = field.getAnnotation(JevScore.class);
        JevLabels labels = field.getAnnotation(JevLabels.class);
        int count = (bool != null ? 1 : 0) + (probability != null ? 1 : 0) + (choice != null ? 1 : 0)
                + (score != null ? 1 : 0) + (labels != null ? 1 : 0);
        Class<?> type = field.getType();
        if (type.isRecord()) {
            if (count != 0) throw invalid(path, "Annotate leaf fields, not a nested record container");
            return compileRecord(type, path, c);
        }
        if (count != 1) throw invalid(path, "Each leaf needs exactly one Jev judgment annotation");
        if (bool != null) {
            if (type != boolean.class && type != Boolean.class) throw invalid(path, "JevBoolean requires boolean or Boolean");
            double threshold = threshold(bool.threshold(), path);
            NoulQuestion question = c.add(NoulQuestion.of(path, instructions(bool.value(), path)));
            return evaluation -> noul(evaluation, question).atLeast(threshold);
        }
        if (probability != null) {
            requireDouble(type, path, "JevProbability");
            NoulQuestion question = c.add(NoulQuestion.of(path, instructions(probability.value(), path)));
            return evaluation -> noul(evaluation, question).probability();
        }
        if (score != null) {
            requireDouble(type, path, "JevScore");
            List<String> levels = List.of(score.levels());
            if (levels.size() < 2 || levels.size() > 10) throw invalid(path, "JevScore requires 2 to 10 levels");
            for (String level : levels) instructions(level, path);
            ScoreQuestion question = c.add(ScoreQuestion.of(path, instructions(score.value(), path), levels));
            return evaluation -> {
                Answer answer = evaluation.answer(question);
                if (!(answer instanceof ScoreAnswer value) || !Double.isFinite(value.score())
                        || value.score() < 0 || value.score() > levels.size() - 1) {
                    throw protocol(path, "Invalid score answer");
                }
                return value.score();
            };
        }
        if (choice != null) {
            boolean optional = type == Optional.class;
            Class<?> enumType = optional ? enumArgument(field.getGenericType(), path) : type;
            EnumDefinition definition = enumDefinition(enumType, path);
            Map<String, Object> criteria = new LinkedHashMap<>(definition.descriptions);
            if (optional) criteria.put(NONE, instructions(choice.noneDescription(), path));
            if (criteria.size() > 255) throw invalid(path, "JevChoice supports at most 255 options including no-match");
            ChoiceQuestion<String> question = c.add(ChoiceQuestion.of(path, instructions(choice.value(), path), criteria));
            return evaluation -> {
                Answer answer = evaluation.answer(question);
                if (!(answer instanceof ChoiceAnswer<?> value) || !(value.choice() instanceof String label)) {
                    throw protocol(path, "Invalid choice answer");
                }
                if (optional && NONE.equals(label)) return Optional.empty();
                Object selected = definition.values.get(label);
                if (selected == null) throw protocol(path, "Unknown enum choice");
                return optional ? Optional.of(selected) : selected;
            };
        }
        if (type != List.class && type != Set.class) throw invalid(path, "JevLabels requires List<Enum> or Set<Enum>");
        double threshold = threshold(labels.threshold(), path);
        String instruction = instructions(labels.value(), path);
        EnumDefinition definition = enumDefinition(enumArgument(field.getGenericType(), path), path);
        List<NoulQuestion> labelQuestions = new ArrayList<>();
        List<Object> constants = new ArrayList<>();
        definition.values.forEach((name, constant) -> {
            Map<String, Object> questionInstructions = new LinkedHashMap<>();
            questionInstructions.put("question", instruction);
            questionInstructions.put("label", name);
            Object description = definition.descriptions.get(name);
            if (description != null) questionInstructions.put("label_description", description);
            labelQuestions.add(c.add(NoulQuestion.of(path + "." + name, questionInstructions)));
            constants.add(constant);
        });
        return evaluation -> {
            List<Object> selected = new ArrayList<>();
            for (int i = 0; i < labelQuestions.size(); i++) {
                if (noul(evaluation, labelQuestions.get(i)).atLeast(threshold)) selected.add(constants.get(i));
            }
            return type == List.class ? List.copyOf(selected)
                    : Collections.unmodifiableSet(new LinkedHashSet<>(selected));
        };
    }

    private static NoulAnswer noul(Evaluation evaluation, NoulQuestion question) {
        Answer answer = evaluation.answer(question);
        if (!(answer instanceof NoulAnswer value)) throw protocol(question.id(), "Invalid probability answer");
        return value;
    }

    private static void requireDouble(Class<?> type, String path, String annotation) {
        if (type != double.class && type != Double.class) throw invalid(path, annotation + " requires double or Double");
    }

    private static Class<?> enumArgument(Type genericType, String path) {
        if (genericType instanceof ParameterizedType parameterized
                && parameterized.getActualTypeArguments().length == 1
                && parameterized.getActualTypeArguments()[0] instanceof Class<?> argument && argument.isEnum()) {
            return argument;
        }
        throw invalid(path, "A concrete enum type argument is required");
    }

    private static EnumDefinition enumDefinition(Class<?> type, String path) {
        if (!type.isEnum()) throw invalid(path, "JevChoice requires an enum or Optional<Enum>");
        Object[] constants = type.getEnumConstants();
        if (constants.length == 0) throw invalid(path, "Enum must declare at least one option");
        Map<String, Object> values = new LinkedHashMap<>();
        Map<String, Object> descriptions = new LinkedHashMap<>();
        for (Object constant : constants) {
            String name = ((Enum<?>) constant).name();
            values.put(name, constant);
            try {
                JevLabel label = type.getField(name).getAnnotation(JevLabel.class);
                descriptions.put(name, label == null ? null : instructions(label.value(), path + "." + name));
            } catch (NoSuchFieldException ex) {
                throw invalid(path, "Cannot read enum description");
            }
        }
        return new EnumDefinition(values, descriptions);
    }

    private static double threshold(double value, String path) {
        if (!Double.isFinite(value) || value < 0 || value > 1) throw invalid(path, "Threshold must be finite and between 0 and 1");
        return value;
    }

    private static String instructions(String value, String path) {
        if (value.isBlank()) throw invalid(path, "Question and criterion descriptions must not be blank");
        return value;
    }

    private static IllegalArgumentException invalid(String path, String message) {
        return new IllegalArgumentException((path.isEmpty() ? "Record" : path) + ": " + message);
    }

    private static JevException protocol(String path, String message) {
        return new JevException(JevException.Kind.PROTOCOL, (path.isEmpty() ? "Record" : path) + ": " + message);
    }

    private interface Reader { Object read(Evaluation evaluation); }
    private record EnumDefinition(Map<String, Object> values, Map<String, Object> descriptions) {}
    private static final class Compilation {
        final List<Question<?>> questions = new ArrayList<>();
        final Set<String> ids = new HashSet<>();
        final Set<Class<?>> visiting = new HashSet<>();
        <Q extends Question<?>> Q add(Q question) {
            if (!ids.add(question.id())) throw invalid(question.id(), "Duplicate field question id");
            questions.add(question);
            return question;
        }
    }
}
