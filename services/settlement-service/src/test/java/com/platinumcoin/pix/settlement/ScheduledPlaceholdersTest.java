package com.platinumcoin.pix.settlement;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;
import org.yaml.snakeyaml.Yaml;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every {@code @Scheduled} delay in this service resolves to a value Spring can actually parse
 * (added in step 44).
 *
 * <h2>Why this test exists — it was written to close a real hole, not a hypothetical one</h2>
 * The step-44 alert watchdog shipped with {@code fixedDelayString = "${…alerts.interval}"} against a
 * property whose value was {@code 30s}. Both halves looked right in isolation: the placeholder resolved,
 * and {@code @ConfigurationProperties} binds {@code "30s"} to a {@link java.time.Duration} happily. But
 * {@code @Scheduled} does <b>not</b> accept the friendly duration form — only a plain number of
 * milliseconds or ISO-8601 — so the context died at startup with a {@code NumberFormatException}.
 *
 * <p><b>And no existing test could have caught it.</b> Every integration test in this platform sets
 * {@code pix.schedulers.enabled=false} (LocalStackTestBase, for good reason: a live poller corrupts
 * unrelated ITs), and every scheduled adapter is {@code @ConditionalOnProperty} on that flag — so the
 * bean is never created in a test, and the annotation is never processed. The failure could only ever
 * appear in {@code docker compose up}, which is exactly where it did appear.
 *
 * <p>So the guard is placed where the bug lives: in the <i>static</i> relationship between the annotation
 * and {@code application.yml}. No Spring context, no containers — read the annotations, read the YAML,
 * and check that each placeholder names a real property whose default is a number Spring will accept.
 * This is the same principle as the Docker API version pinned in the parent POM (CLAUDE.md): a known
 * trap is fixed in the build, not in something someone has to remember.
 */
class ScheduledPlaceholdersTest {

    /** {@code ${some.property:default}} — captures the key and the default separately. */
    private static final Pattern PLACEHOLDER = Pattern.compile("^\\$\\{([^:}]+)(?::([^}]*))?}$");

    /** {@code ${ENV_VAR:12345}} — the shape every delay default takes in application.yml. */
    private static final Pattern ENV_DEFAULT = Pattern.compile("^\\$\\{[^:}]+:([^}]*)}$");

    private static final JavaClasses SETTLEMENT_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.platinumcoin.pix.settlement");

    @Test
    void everyScheduledDelayNamesARealPropertyWhoseValueSpringCanParse() throws Exception {
        Map<String, Object> yaml = loadApplicationYaml();

        List<String> scheduled = new ArrayList<>();
        for (var javaClass : SETTLEMENT_CLASSES) {
            for (JavaMethod method : javaClass.getMethods()) {
                method.tryGetAnnotationOfType(Scheduled.class).ifPresent(annotation -> {
                    String expression = annotation.fixedDelayString();
                    assertThat(expression)
                            .as("%s.%s must schedule by property, never by a hard-coded delay",
                                    javaClass.getSimpleName(), method.getName())
                            .isNotBlank();
                    scheduled.add(javaClass.getSimpleName() + "." + method.getName() + " -> " + expression);
                    assertPlaceholderResolvesToANumber(
                            expression, yaml, javaClass.getSimpleName() + "." + method.getName());
                });
            }
        }

        assertThat(scheduled)
                .as("this service has scheduled adapters; finding none means the scan silently broke")
                .isNotEmpty();
    }

    private void assertPlaceholderResolvesToANumber(
            String expression, Map<String, Object> yaml, String where) {
        Matcher matcher = PLACEHOLDER.matcher(expression);
        assertThat(matcher.matches())
                .as("%s: '%s' is not a simple ${property} placeholder", where, expression)
                .isTrue();

        String key = matcher.group(1);
        Object raw = lookup(yaml, key);
        assertThat(raw)
                .as("%s: application.yml has no property '%s' — the context would fail at startup, and "
                        + "no integration test would notice because schedulers are disabled there", where, key)
                .isNotNull();

        // The YAML value is itself an env-var placeholder (${ENV:default}); Spring resolves it to the
        // default when the variable is unset, which is precisely the case in every local run.
        String value = String.valueOf(raw);
        Matcher envDefault = ENV_DEFAULT.matcher(value);
        String effective = envDefault.matches() ? envDefault.group(1) : value;

        assertThat(effective)
                .as("%s: '%s' resolves to '%s'. @Scheduled accepts milliseconds or ISO-8601 (PT30S) — "
                        + "NOT the '30s' form that @ConfigurationProperties Duration binding accepts. "
                        + "That mismatch is a startup crash, not a compile error.", where, key, effective)
                .matches("\\d+|PT.+");
    }

    /** Walks a dotted key ({@code pix.settlement.alerts.fixed-delay-ms}) into the nested YAML maps. */
    @SuppressWarnings("unchecked")
    private Object lookup(Map<String, Object> yaml, String dottedKey) {
        Object current = yaml;
        for (String segment : dottedKey.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = ((Map<String, Object>) map).get(segment);
        }
        return current;
    }

    private Map<String, Object> loadApplicationYaml() throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("application.yml")) {
            assertThat(in).as("application.yml must be on the test classpath").isNotNull();
            return new Yaml().load(in);
        }
    }
}
