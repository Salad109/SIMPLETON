package io.salad109.conjunctiondetector.conjunction;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ValidatePropertiesTest {

    private static void validateWindow(int lookaheadHours, double stepSeconds, int subwindowCount) {
        ConjunctionService.validate(72.0, 55.38, 5.0, lookaheadHours, stepSeconds, 50, subwindowCount);
    }

    @ParameterizedTest(name = "{0}h window, {1}s step, {2} subwindow(s)")
    @CsvSource({
            "24, 9, 4",      // the tuned configuration
            "24, 9, 1",      // subwindowing disabled
            "168, 9, 28",    // 7 days at the recommended subwindow count
    })
    void wholeStepSubwindowsAreAccepted(int lookaheadHours, double stepSeconds, int subwindowCount) {
        assertThatCode(() -> validateWindow(lookaheadHours, stepSeconds, subwindowCount))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "{0}h window, {1}s step, {2} subwindow(s)")
    @CsvSource({
            "24, 9, 7",      // last step lands 3.857s short of the boundary
            "24, 9, 28",     // subwindows overlap by 1.286s
            "24, 9, 9601",   // subwindow shorter than one step
    })
    void subwindowsSplittingMidStepAreRejected(int lookaheadHours, double stepSeconds, int subwindowCount) {
        assertThatThrownBy(() -> validateWindow(lookaheadHours, stepSeconds, subwindowCount))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void nonPositiveToleranceIsRejected() {
        assertThatThrownBy(() -> ConjunctionService.validate(0.0, 55.38, 5.0, 24, 9.0, 50, 4))
                .isInstanceOf(IllegalStateException.class);
    }
}
