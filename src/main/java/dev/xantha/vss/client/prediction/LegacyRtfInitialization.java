package dev.xantha.vss.client.prediction;

import java.util.Objects;

/**
 * The ordered semantics of ReTerraForged's RandomState initializer for prediction, separated from its registry access.
 * It follows RTF's own MixinRandomState.initialize: the bound level gets the preset and, when the router carries
 * CellSampler markers, a generator context; any other level is a native dimension and keeps neither.
 */
final class LegacyRtfInitialization {
    private LegacyRtfInitialization() { }

    interface State<P, C> {
        boolean hasMarkers();
        long seed();
        P preset();
        C context();
        void preset(P value);
        void context(C value);
        void clear();
    }

    interface Inputs<P, C> {
        void visitAdditional();
        void applyTerraBlender();
        P preset();
        C build(P preset, long seed);
    }

    /** bound: the Overworld, or a level on its noise settings whose server RandomState holds an RTF context. */
    static <P, C> void initialize(boolean bound, State<P, C> state, Inputs<P, C> inputs) {
        Objects.requireNonNull(state, "prediction state");
        Objects.requireNonNull(inputs, "prediction registry inputs");
        if (!bound) {
            nativeDimension(state);
            return;
        }
        inputs.visitAdditional();
        inputs.applyTerraBlender();
        P preset = inputs.preset();
        if (preset == null) throw failure("required RTF snapshot contains no active preset");
        state.preset(preset);
        if (state.hasMarkers()) {
            C context = inputs.build(preset, state.seed());
            if (context == null) throw failure("RTF CellSampler markers require a complete context, but the factory returned null");
            state.context(context);
        }
        validate(state);
    }

    static void nativeDimension(State<?, ?> state) {
        if (state.hasMarkers() || state.preset() != null || state.context() != null) {
            throw failure("required RTF markers or owned context are outside the levels ReTerraForged bound on the server; refusing partial terrain prediction");
        }
        state.clear();
    }

    static void validate(State<?, ?> state) {
        if (state.hasMarkers() && (state.preset() == null || state.context() == null)) {
            throw failure("RTF CellSampler markers are present without initialized preset/context; refusing partial terrain prediction");
        }
        if (state.context() != null && state.preset() == null) {
            throw failure("RTF prediction context has no owning preset");
        }
    }

    static IllegalStateException failure(String detail) {
        return new IllegalStateException("VSS/RTF prediction: " + detail);
    }
}
