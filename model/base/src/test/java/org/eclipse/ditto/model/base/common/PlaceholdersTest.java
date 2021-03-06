/*
 * Copyright (c) 2017 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.ditto.model.base.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mutabilitydetector.unittesting.MutabilityAssert.assertInstancesOf;
import static org.mutabilitydetector.unittesting.MutabilityMatchers.areImmutable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import org.eclipse.ditto.model.base.exceptions.DittoRuntimeException;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests {@link Placeholders}.
 */
public class PlaceholdersTest {

    private static final String REPLACER_KEY_1 = "my:arbitrary:replacer1";
    private static final String REPLACER_1 = "{{ " + REPLACER_KEY_1 + " }}";
    private static final String LEGACY_REPLACER_KEY = "request.subjectId";
    private static final String LEGACY_REPLACER = "${" + LEGACY_REPLACER_KEY + "}";
    private static final String LEGACY_REPLACED = "some:subject";
    private static final String REPLACED_1 = "firstReplaced";
    private static final String REPLACER_KEY_2 = "my:arbitrary:replacer2";
    private static final String REPLACER_2 = "{{ " + REPLACER_KEY_2 + " }}";
    private static final String REPLACED_2 = "secondReplaced";

    private static final String UNKNOWN_REPLACER_KEY = "unknown:unknown";
    private static final String UNKNOWN_REPLACER = "{{ " + UNKNOWN_REPLACER_KEY + " }}";
    private static final String UNKNOWN_LEGACY_REPLACER_KEY = "unknown.unknown";
    private static final String UNKNOWN_LEGACY_REPLACER = "${" + UNKNOWN_LEGACY_REPLACER_KEY + "}";

    private Function<String, Optional<String>> replacerFunction;
    private Function<String, DittoRuntimeException> unresolvedInputHandler;

    @Before
    public void init() {
        final Map<String, String> replacementDefinitions = new LinkedHashMap<>();
        replacementDefinitions.put(REPLACER_KEY_1, REPLACED_1);
        replacementDefinitions.put(REPLACER_KEY_2, REPLACED_2);
        replacementDefinitions.put(LEGACY_REPLACER_KEY, LEGACY_REPLACED);
        replacerFunction = input -> Optional.ofNullable(replacementDefinitions.get(input));
        unresolvedInputHandler = unresolvedInput ->
                DittoRuntimeException.newBuilder("test", HttpStatusCode.BAD_REQUEST)
                        .message(unresolvedInput)
                        .build();
    }

    @Test
    public void assertImmutability() {
        assertInstancesOf(Placeholders.class, areImmutable());
    }

    @Test
    public void substituteFailsWithNullInput() {
        assertThatExceptionOfType(NullPointerException.class)
                .isThrownBy(() -> Placeholders.substitute(null, replacerFunction, unresolvedInputHandler));
    }

    @Test
    public void substituteFailsWithNullReplacerFunction() {
        assertThatExceptionOfType(NullPointerException.class)
                .isThrownBy(() -> Placeholders.substitute("doesNotMatter", null,
                        unresolvedInputHandler));
    }

    @Test
    public void substituteFailsWithNullUnresolvedInputHandler() {
        assertThatExceptionOfType(NullPointerException.class)
                .isThrownBy(() -> Placeholders.substitute("doesNotMatter", replacerFunction,
                        null));
    }

    @Test
    public void substituteReturnsInputWhenInputDoesNotContainReplacers() {
        final String input = "withoutReplacers";

        final String substituted = Placeholders.substitute(input, replacerFunction, unresolvedInputHandler);

        assertThat(substituted).isSameAs(input);
    }

    @Test
    public void substituteReturnsReplacedWhenInputContainsOnlyPlaceholder() {
        final String substituted = Placeholders.substitute(REPLACER_1, replacerFunction, unresolvedInputHandler);

        assertThat(substituted).isEqualTo(REPLACED_1);
    }

    @Test
    public void substituteReturnsReplacedWhenInputContainsPlaceholderWithoutSpaces() {
        final String substituted =
                Placeholders.substitute("{{" + REPLACER_KEY_1 + "}}", replacerFunction, unresolvedInputHandler);

        assertThat(substituted).isEqualTo(REPLACED_1);
    }

    @Test
    public void substituteReturnsReplacedWhenInputContainsMultiplePlaceholdersWithoutSpaces() {
        final String substituted =
                Placeholders.substitute("{{" + REPLACER_KEY_1 + "}}" + "{{" + REPLACER_KEY_2 + "}}",
                        replacerFunction, unresolvedInputHandler);

        assertThat(substituted).isEqualTo(REPLACED_1 + REPLACED_2);
    }

    @Test
    public void substituteReturnsReplacedWhenInputContainsPlaceholderWithManySpace() {
        final String substituted = Placeholders.substitute("{{    " + REPLACER_KEY_1 + "      }}",
                replacerFunction, unresolvedInputHandler);

        assertThat(substituted).isEqualTo(REPLACED_1);
    }

    @Test
    public void substituteReturnsReplacedInputWhenInputContainsSurroundedPlaceholder() {
        final String substituted =
                Placeholders.substitute("a" + REPLACER_1 + "z", replacerFunction, unresolvedInputHandler);

        assertThat(substituted).isEqualTo("a" + REPLACED_1 + "z");
    }

    @Test
    public void substituteReturnsReplacedInputWhenInputContainsMultiplePlaceholders() {
        final String input = "a" + REPLACER_1 + "b" + REPLACER_2 + "c";

        final String substituted = Placeholders.substitute(input, replacerFunction, unresolvedInputHandler);

        final String expectedOutput = "a" + REPLACED_1 + "b" + REPLACED_2 + "c";
        assertThat(substituted).isEqualTo(expectedOutput);
    }

    @Test
    public void substituteReturnsReplacedWhenInputContainsOnlyLegacyPlaceholder() {
        final String substituted =
                Placeholders.substitute(LEGACY_REPLACER, replacerFunction, unresolvedInputHandler);

        assertThat(substituted).isEqualTo(LEGACY_REPLACED);
    }

    @Test
    public void substituteReturnsReplacedInputWhenInputContainsBothNewAndLegacyPlaceholders() {
        final String input = "a" + REPLACER_1 + "b" + LEGACY_REPLACER + "c";

        final String substituted = Placeholders.substitute(input, replacerFunction, unresolvedInputHandler);

        final String expectedOutput = "a" + REPLACED_1 + "b" + LEGACY_REPLACED + "c";
        assertThat(substituted).isEqualTo(expectedOutput);
    }

    @Test
    public void substituteThrowsWhenReplacerFunctionReturnsNullForPlaceholder() {
        assertThatExceptionOfType(DittoRuntimeException.class)
                .isThrownBy(() -> Placeholders.substitute(UNKNOWN_REPLACER, (placeholder) -> Optional.empty(),
                        unresolvedInputHandler))
                .withMessage(UNKNOWN_REPLACER)
                .matches(e -> "test".equals(e.getErrorCode()));
    }

    @Test
    public void substituteThrowsForUnknownLegacyPlaceholder() {
        assertThatExceptionOfType(DittoRuntimeException.class)
                .isThrownBy(() -> Placeholders.substitute(UNKNOWN_LEGACY_REPLACER, (placeholder) -> Optional.empty(),
                        unresolvedInputHandler))
                .withMessage(UNKNOWN_LEGACY_REPLACER)
                .matches(e -> "test".equals(e.getErrorCode()));

    }

    @Test
    public void substituteThrowsWhenUnresolvedPlaceholdersRemain() {
        final String nestedPlaceholder = "{{ " + REPLACER_1 + " }}";

        assertThatExceptionOfType(DittoRuntimeException.class)
                .isThrownBy(() -> Placeholders.substitute(nestedPlaceholder, replacerFunction, unresolvedInputHandler))
                .withMessageContaining("{{ " + REPLACED_1 + " }}");
    }

    /**
     * Nesting legacy-placeholders does not work. In our case, null will be returned by the replacerFunction.
     * Normally, replacerFunctions should throw a DittoRuntimeException in this case.
     */
    @Test
    public void substituteThrowsWhenInputContainsNestedLegacyPlaceholder() {
        final String nestedPlaceholder = "${" + LEGACY_REPLACER + "}";

        assertThatExceptionOfType(DittoRuntimeException.class)
                .isThrownBy(() -> Placeholders.substitute(nestedPlaceholder, (placeholder) -> Optional.empty(),
                        unresolvedInputHandler))
                .withMessage(nestedPlaceholder);
    }

    @Test
    public void containsReturnsTrueWhenInputContainsPlaceholder() {
        final String input = "a" + REPLACER_1 + "z";

        final boolean contains = Placeholders.containsAnyPlaceholder(input);

        assertThat(contains).isTrue();
    }

    @Test
    public void containsReturnsTrueWhenInputContainsLegacyPlaceholder() {
        final String input = "a" + LEGACY_REPLACER + "z";

        final boolean contains = Placeholders.containsAnyPlaceholder(input);

        assertThat(contains).isTrue();
    }

    @Test
    public void containsReturnsTrueWhenInputContainsBothNewAndLegacyPlaceholders() {
        final String input = "a" + REPLACER_1 + "b" + LEGACY_REPLACER + "c";

        final boolean contains = Placeholders.containsAnyPlaceholder(input);

        assertThat(contains).isTrue();
    }

    @Test
    public void containsReturnsFalseWhenInputContainsPlaceholderStartOnly() {
        final String input = "a{{z";

        final boolean contains = Placeholders.containsAnyPlaceholder(input);

        assertThat(contains).isFalse();
    }

    @Test
    public void containsReturnsFalseWhenInputContainsPlaceholderEndOnly() {
        final String input = "a}}z";

        final boolean contains = Placeholders.containsAnyPlaceholder(input);

        assertThat(contains).isFalse();
    }

    @Test
    public void containsReturnsFailsWhenInputDoesNotContainAnyPlaceholder() {
        final String input = "abc";

        final boolean contains = Placeholders.containsAnyPlaceholder(input);

        assertThat(contains).isFalse();
    }
}
