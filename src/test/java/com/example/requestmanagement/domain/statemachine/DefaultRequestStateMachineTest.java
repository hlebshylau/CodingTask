package com.example.requestmanagement.domain.statemachine;

import com.example.requestmanagement.domain.exception.InvalidTransitionException;
import com.example.requestmanagement.domain.model.RequestEvent;
import com.example.requestmanagement.domain.model.RequestStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Stream;

import static com.example.requestmanagement.domain.model.RequestEvent.ACCEPT;
import static com.example.requestmanagement.domain.model.RequestEvent.DELETE;
import static com.example.requestmanagement.domain.model.RequestEvent.PUBLISH;
import static com.example.requestmanagement.domain.model.RequestEvent.REJECT;
import static com.example.requestmanagement.domain.model.RequestEvent.VERIFY;
import static com.example.requestmanagement.domain.model.RequestStatus.ACCEPTED;
import static com.example.requestmanagement.domain.model.RequestStatus.CREATED;
import static com.example.requestmanagement.domain.model.RequestStatus.DELETED;
import static com.example.requestmanagement.domain.model.RequestStatus.PUBLISHED;
import static com.example.requestmanagement.domain.model.RequestStatus.REJECTED;
import static com.example.requestmanagement.domain.model.RequestStatus.VERIFIED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultRequestStateMachineTest {

    private static final Map<String, RequestStatus> PERMITTED = Map.of(
            key(CREATED, VERIFY), VERIFIED,
            key(CREATED, DELETE), DELETED,
            key(VERIFIED, ACCEPT), ACCEPTED,
            key(VERIFIED, REJECT), REJECTED,
            key(ACCEPTED, REJECT), REJECTED,
            key(ACCEPTED, PUBLISH), PUBLISHED
    );

    private final RequestStateMachine stateMachine = new DefaultRequestStateMachine();

    @ParameterizedTest(name = "{0} --{1}--> {2}")
    @MethodSource("permittedTransitions")
    void appliesPermittedTransition(RequestStatus from, RequestEvent event, RequestStatus expected) {
        assertThat(stateMachine.transition(from, event)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0} --{1}--> rejected")
    @MethodSource("forbiddenTransitions")
    void rejectsForbiddenTransition(RequestStatus from, RequestEvent event) {
        assertThatThrownBy(() -> stateMachine.transition(from, event))
                .isInstanceOf(InvalidTransitionException.class)
                .hasMessage("Cannot apply " + event + " event in " + from + " state")
                .satisfies(ex -> {
                    InvalidTransitionException invalid = (InvalidTransitionException) ex;
                    assertThat(invalid.getCurrentState()).isEqualTo(from);
                    assertThat(invalid.getEvent()).isEqualTo(event);
                });
    }

    @ParameterizedTest
    @EnumSource(value = RequestStatus.class, names = {"PUBLISHED", "REJECTED", "DELETED"})
    void terminalStatesRejectEveryEvent(RequestStatus terminal) {
        assertThat(terminal.isTerminal()).isTrue();
        for (RequestEvent event : RequestEvent.values()) {
            assertThatThrownBy(() -> stateMachine.transition(terminal, event))
                    .isInstanceOf(InvalidTransitionException.class);
        }
    }

    @Test
    void matrixCoversAllThirtyCombinations() {
        assertThat(permittedTransitions().count() + forbiddenTransitions().count())
                .isEqualTo((long) RequestStatus.values().length * RequestEvent.values().length)
                .isEqualTo(30);
        assertThat(permittedTransitions().count()).isEqualTo(6);
    }

    @Test
    void forbidsTransitionsExplicitlyListedInSpecification() {
        assertThatThrownBy(() -> stateMachine.transition(CREATED, ACCEPT)).isInstanceOf(InvalidTransitionException.class);
        assertThatThrownBy(() -> stateMachine.transition(CREATED, PUBLISH)).isInstanceOf(InvalidTransitionException.class);
        assertThatThrownBy(() -> stateMachine.transition(VERIFIED, PUBLISH)).isInstanceOf(InvalidTransitionException.class);
        assertThatThrownBy(() -> stateMachine.transition(ACCEPTED, VERIFY)).isInstanceOf(InvalidTransitionException.class);
        assertThatThrownBy(() -> stateMachine.transition(PUBLISHED, DELETE)).isInstanceOf(InvalidTransitionException.class);
    }

    @Test
    void rejectsNullArguments() {
        assertThatThrownBy(() -> stateMachine.transition(null, VERIFY)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> stateMachine.transition(CREATED, null)).isInstanceOf(NullPointerException.class);
    }

    static Stream<Arguments> permittedTransitions() {
        return allCombinations()
                .filter(args -> PERMITTED.containsKey(key(args[0], args[1])))
                .map(args -> Arguments.of(args[0], args[1], PERMITTED.get(key(args[0], args[1]))));
    }

    static Stream<Arguments> forbiddenTransitions() {
        return allCombinations()
                .filter(args -> !PERMITTED.containsKey(key(args[0], args[1])))
                .map(args -> Arguments.of(args[0], args[1]));
    }

    private static Stream<Enum<?>[]> allCombinations() {
        return Arrays.stream(RequestStatus.values())
                .flatMap(status -> Arrays.stream(RequestEvent.values())
                        .map(event -> new Enum<?>[]{status, event}));
    }

    private static String key(Enum<?> status, Enum<?> event) {
        return status.name() + ":" + event.name();
    }
}
