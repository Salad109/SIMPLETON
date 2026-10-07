package io.salad109.conjunctiondetector.conjunction;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ValidatePropertiesTest {

    private static void validateWindow(int lookaheadHours, double stepSeconds, int subwindowCount) {
        ConjunctionService.validate(5.0, lookaheadHours, stepSeconds, 252, subwindowCount);
    }

    @ParameterizedTest(name = "{0}h window, {1}s step, {2} subwindow(s)")
    @CsvSource({
            "24, 12, 4",     // the deployed configuration
            "24, 12, 1",     // subwindowing disabled
            "168, 12, 28",   // 7 days at the recommended subwindow count
    })
    void wholeStepSubwindowsAreAccepted(int lookaheadHours, double stepSeconds, int subwindowCount) {
        assertThatCode(() -> validateWindow(lookaheadHours, stepSeconds, subwindowCount))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "{0}h window, {1}s step, {2} subwindow(s)")
    @CsvSource({
            "24, 12, 7",     // subwindows overlap by 5.143s
            "24, 12, 28",    // last step lands 1.714s short of the boundary
            "24, 12, 9601",  // subwindow shorter than one step
    })
    void subwindowsSplittingMidStepAreRejected(int lookaheadHours, double stepSeconds, int subwindowCount) {
        assertThatThrownBy(() -> validateWindow(lookaheadHours, stepSeconds, subwindowCount))
                .isInstanceOf(IllegalStateException.class);
    }
}
